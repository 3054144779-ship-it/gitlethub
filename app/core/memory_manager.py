"""
记忆分层管理

"""
import asyncio
import json
import uuid
from typing import List

import tiktoken
from langchain_core.documents import Document
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AnyMessage, HumanMessage, AIMessage
from redis.asyncio import Redis
from pymilvus import MilvusClient

from client.embeding import EmbeddingModel
from repository.milvus_repo import MilvusRepo
from schema.base_model import Metadata
from schema.domain import MilvusPO

class MemoryManager:
    """
    记忆分层管理服务

    1.工作记忆：冷热分离
    2.情景记忆：文本压缩
    3.语义记忆：数据向量化入库
    4.程序性记忆：检索记忆

    """

    # 工作记忆 token 阈值，超过则从最旧的冷消息开始压缩淘汰
    DEFAULT_TOKEN_THRESHOLD = 4000

    def __init__(
            self,
            redis_client: Redis,
            milvus_client: MilvusRepo,
            compress_client: BaseChatModel,
            embedding_client: EmbeddingModel,
            token_threshold: int = DEFAULT_TOKEN_THRESHOLD,
    ):
        self.redis_client = redis_client
        self.milvus_client = milvus_client
        self.compress_client = compress_client
        self.token_threshold = token_threshold
        self.embedding_client = embedding_client
        # 获取编码器，进程内复用
        self._encode = tiktoken.get_encoding(encoding_name="cl100k_base")

    async def init_session(self, session_id: str, meta: dict):
        """
        会话首次创建时，把元信息落库一次（与消息本身解耦）
        :param session_id: 会话ID
        :param meta: 含 user_id/repository_id/file_path/commit_sha/branch_name
        """
        key = f"{session_id}:meta"
        # 幂等：会话已初始化则直接返回，避免每轮对话重置 compressed_count / has_rag_data
        if await self.redis_client.exists(key):
            return
        await self.redis_client.hset(
            key,
            mapping={
                "user_id": meta["user_id"],
                "repository_id": meta["repository_id"],
                "file_path": meta.get("file_path", ""),
                "commit_sha": meta.get("commit_sha", ""),
                "branch_name": meta.get("branch_name", ""),
                "has_rag_data": False, # 是否有RAG数据
                "compressed_count": 0, # 压缩消息次数计数
            },
        )

    async def _get_session_meta(self, session_id: str) -> dict:
        """读取会话元信息；未开 decode_responses，hgetall 返回 bytes 需解码"""
        raw = await self.redis_client.hgetall(f"{session_id}:meta")
        meta = {
            (k.decode() if isinstance(k, bytes) else k):
            (v.decode() if isinstance(v, bytes) else v)
            for k, v in raw.items()
        }
        meta["user_id"] = int(meta.get("user_id") or 0)
        meta["repository_id"] = int(meta.get("repository_id") or 0)
        return meta

    async def add_message(self, session_id: str, message: AnyMessage):
        """
        添加消息(存入Redis)
        :return:
        """
        message_id = message.id or str(uuid.uuid4())
        content = message.content if isinstance(message.content, str) else str(message.content)
        tokens = await self._get_count_tokens(content) # 获取消息的token数
        payload = json.dumps(
            {
                "id": message_id,
                "type": message.type,
                "content": content,
                "tokens": tokens,
            },
            ensure_ascii=False,
        )

        # 使用管道批量执行命令，保证原子性
        with await self.redis_client.pipeline(transaction=True) as pipe:
            pipe.rpush(session_id, payload) # {session_id : [payload={id, type, content, tokens}]}
            pipe.incrby(self._tokens_key(session_id), tokens) # {session_id:tokens : total_tokens} 增加token数
            await pipe.execute() # 执行管道中的命令

        # 检查token数，如果超过阈值则进行压缩
        await self._check_tokens(session_id)

    def _tokens_key(self, session_id: str):
        """
        获取会话的token数
        :param session_id: 会话ID
        :return: token数
        """
        return f"{session_id}:tokens"

    async def _get_count_tokens(self, text: str):
        return len(self._encode.encode(text))

    async def _get_total_tokens(self, session_id: str):
        total = await self.redis_client.get(self._tokens_key(session_id))
        return int(total) if total else 0

    async def _check_tokens(self, session_id: str):
        """
        检查消息的token数
        :param session_id: 会话ID
        :return: token数
        冷热分离：
        如果当前的token数超过阈值，则将部分消息压缩，以确保总token数不超过阈值。
        """

        current_tokens = await self._get_total_tokens(session_id)
        if current_tokens < self.token_threshold:
            # 如果token数小于阈值，则不需要压缩
            return

        # 如果token数超过阈值，则进行压缩，仅保留最近2条消息
        raw_messages = await self.redis_client.lrange(session_id, 0, -3)
        removed_tokens= 0
        to_remove = 0
        cold_contents = []
        is_less_than_threshold = False

        for raw in raw_messages:
            if current_tokens - removed_tokens <= self.token_threshold:
                is_less_than_threshold = True
            data = json.loads(raw)
            removed_tokens += data.get("tokens", 0)
            cold_contents.append(data.get("content", ""))
            to_remove += 1

        if to_remove == 0:
            # 如果没有需要压缩的消息，则返回
            return

        # 压缩消息，存入到Redis中
        compressed = await self._compress_messages(session_id, cold_contents)
        compressed_tokens = compressed.get("tokens", 0)
        compressed_message = compressed.get("compressed_message")
        message_id = compressed_message.id or str(uuid.uuid4())
        content = compressed_message.content if isinstance(compressed_message.content, str) else str(compressed_message.content)

        payload = json.dumps(
            {
                "id": message_id,
                "type": "summary", # 压缩消息类型，表示总结类型
                "content": content,
                "tokens": compressed_tokens,
            },
            ensure_ascii=False,
        )

        # 压缩消息
        with await self.redis_client.pipeline(transaction=True) as pipe:
            pipe.lpush(session_id, payload)
            pipe.ltrim(session_id, to_remove, -1)  # 只保留最近2条消息
            pipe.decrby(self._tokens_key(session_id), removed_tokens - compressed_tokens)  # 减少token数
            pipe.hincrby(f"{session_id}:meta", "compressed_count", 1) # 压缩消息次数计数加1
            await pipe.execute()  # 执行管道中的命令

        if not is_less_than_threshold:
            raw_messages = await self.redis_client.lrange(session_id, 0, -1)
            with await self.redis_client.pipeline(transaction=True) as pipe:
                pipe.ltrim(session_id, 0, -1)  # 只保留最近2条消息
                pipe.set(self._tokens_key(session_id), 0)  # 清空会话
                pipe.hincrby(f"{session_id}:meta", "has_rag_data", True) # 设置 has_rag_data 标记为True
                await pipe.execute()  # 执行管道中的命令

            # json.loads 直接读 bytes，取出每条消息的有效载荷 payload（str）
            # 消息合并，防止召回出错
            contents = ", ".join([str(json.loads(raw)) for raw in raw_messages])
            # 如果保留两条依旧大于阈值，则进行RAG化，直接把这长文本切片，存入 Milvus（打上当前 session_id 的标签）
            vector = await asyncio.to_thread(
                self.embedding_client.to_vector,
                [Document(page_content=contents)]
            )
            dense_docs, sparse_docs = vector[0]
            meta = await self._get_session_meta(session_id)
            milvus_po = MilvusPO(
                id=None,
                user_id=meta["user_id"],
                repository_id=meta["repository_id"],
                file_path=meta["file_path"],
                commit_sha=meta["commit_sha"],
                branch_name=meta["branch_name"],
                dense_vector=dense_docs.tolist(),
                sparse_vector=sparse_docs,
                language=None,
                text=contents,
                metadata=Metadata(session_id=session_id),
                content_type="raw_text"
            )
            self.milvus_client.insert(milvus_po.to_milvus_dict())

    async def _compress_messages(self, session_id: str, messages: List[str]) -> dict:
        """
        压缩消息
        :param session_id: 会话ID
        :param messages: 消息列表
        :return: 压缩后的消息
        """
        prompt = f"""
        你是一个对话记忆压缩器。你的任务是把一段多轮历史对话压缩成简洁、结构化的摘要，用于长期记忆存储。压缩后的摘要会在后续对话中作为上下文重新注入，因此必须保留所有对后续交互有价值的信息。

        ## 必须保留的信息
        - 用户的核心意图、需求、偏好和约束条件
        - 已确认的关键事实、结论与决策（尤其是技术选型、方案取舍的原因）
        - 重要实体：文件路径、函数名、类名、代码符号、仓库名、配置项、报错信息等，必须原样保留，禁止改写、翻译或缩写
        - 未完成的任务、待办事项，以及后续可能被引用的上下文
        - 用户明确表达过的偏好（如代码风格、语言、工具选择）
        
        ## 应当丢弃的信息
        - 寒暄、客套、情绪化表达
        - 重复或冗余的内容
        - 与后续交互无关的中间过程细节
        
        ## 输出要求
        - 使用第三人称要点式陈述，条理清晰
        - 严格基于原始对话，禁止编造、推测或补充原文中不存在的信息
        - 在保留关键信息的前提下尽可能压缩到最短
        - 直接输出摘要正文，不要添加“以下是摘要”等前缀，不要用代码块包裹
        
        ## 待压缩的历史对话
        { messages }
        
        """
        compressed_message = await self.compress_client.ainvoke(prompt)
        tokens = await self._get_count_tokens(str(compressed_message.content))
        return {
            "compressed_message": compressed_message,
            "tokens": tokens,
        }

    async def _get_milvus_messages(self, session_id: str, query: str, meta: dict) -> List[str]:
        meta = self._get_session_meta(session_id)
        vector = await asyncio.to_thread(
            self.embedding_client.to_vector,
            [Document(page_content=query)]
        )
        dense_docs, sparse_docs = vector[0]
        milvus_messages = await asyncio.to_thread(
            self.milvus_client.retrieval,
            dense_anns_field="dense_vector",
            sparse_anns_field="sparse_vector",
            dense_vector=dense_docs.tolist(),
            sparse_vector=sparse_docs,
            user_id=meta["user_id"],
            repo_id=meta["repository_id"],
            output_fields=["text"],
            filter_expr=f'metadata["session_id"] == {session_id}',
            top_k=5
        )

        messages = []
        for message in milvus_messages:
            for hit in message:
                messages.append(hit.get("text", ""))

        return messages

    async def get_history_messages(self, session_id: str, query: str) -> dict:
        """
        获取历史消息
        :param query: 用户消息
        :param session_id: 会话ID
        :return: 消息列表
        """
        meta = await self._get_session_meta(session_id)
        milvus_messages = ""
        if meta.get("has_rag_data", False):
            # 如果有RAG数据，则从Milvus中获取
            milvus_messages = self._get_milvus_messages(session_id, query, meta)
        messages_byte = await self.redis_client.get(session_id)
        redis_messages = [str(json.loads(message)) for message in messages_byte]
        return {
            "milvus_messages": "milvus中检索到的会话上下文（可能为空）：" + str(milvus_messages) if milvus_messages else "",
            "redis_messages": "最近的会话上下文：" + str(redis_messages)
        }

if __name__ == '__main__':
    print(AIMessage(content="Hello, world!"))


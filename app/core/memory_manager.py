"""
记忆分层管理

"""
import asyncio
import hashlib
import json
import uuid
from typing import List

import tiktoken
from langchain_core.documents import Document
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AnyMessage, HumanMessage, AIMessage
from redis.asyncio import Redis
from pymilvus import MilvusClient
from langchain_text_splitters import RecursiveCharacterTextSplitter

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
        self.splitter = RecursiveCharacterTextSplitter(
                separators=["\n\n", "\n", " ", ""],
                chunk_size=1000,
                chunk_overlap=100,
                length_function=len,
            )

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
                "has_rag_data": 0, # 是否有RAG数据
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
        meta["has_rag_data"] = bool(int(meta.get("has_rag_data") or 0))
        return meta

    async def add_message(self, session_id: str, messages: List[AnyMessage]):
        """
        批量添加消息(存入Redis)
        :param session_id: 会话ID
        :param messages: 消息列表
        :return:
        """
        if not messages:
            return

        payloads = []
        total_tokens = 0
        for message in messages:
            message_id = message.id or str(uuid.uuid4())
            content = message.content if isinstance(message.content, str) else str(message.content)
            tokens = await self._get_count_tokens(content)  # 获取单条消息的token数
            payloads.append(
                json.dumps(
                    {
                        "id": message_id,
                        "type": message.type,
                        "content": content,
                        "tokens": tokens,
                    },
                    ensure_ascii=False,
                )
            )
            total_tokens += tokens

        # 使用管道批量执行命令，保证原子性
        async with self.redis_client.pipeline(transaction=True) as pipe:
            pipe.rpush(session_id, *payloads)  # {session_id : [payload={id, type, content, tokens}]} 按顺序追加
            pipe.incrby(self._tokens_key(session_id), total_tokens)  # {session_id:tokens : total_tokens} 累加本轮总token数
            await pipe.execute()  # 执行管道中的命令

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
        async with self.redis_client.pipeline(transaction=True) as pipe:
            pipe.lpush(session_id, payload)
            pipe.ltrim(session_id, to_remove, -1)  # 只保留最近2条消息
            pipe.decrby(self._tokens_key(session_id), removed_tokens - compressed_tokens)  # 减少token数
            pipe.hincrby(f"{session_id}:meta", "compressed_count", 1) # 压缩消息次数计数加1
            await pipe.execute()  # 执行管道中的命令

        if not is_less_than_threshold:
            raw_messages = await self.redis_client.lrange(session_id, 0, -1)
            async with self.redis_client.pipeline(transaction=True) as pipe:
                pipe.ltrim(session_id, 0, -1)  # 只保留最近2条消息
                pipe.set(self._tokens_key(session_id), 0)  # 清空会话
                await pipe.execute()  # 执行管道中的命令

            # json.loads 直接读 bytes，取出每条消息的有效载荷 payload（str）
            # 消息合并，防止召回出错
            contents = ", ".join([str(json.loads(raw)) for raw in raw_messages])
            # 如果保留两条依旧大于阈值，则进行RAG化，直接把这长文本切片，存入 Milvus（打上当前 session_id 的标签）
            # 切分长文本，避免超过 Milvus varchar 字段 65535 限制

            chunks = self.splitter.split_text(contents)

            meta = await self._get_session_meta(session_id)

            # 关键改造：不再把原始碎片直接入库，而是先抽取“脱离本次会话仍成立”的稳定事实
            facts = await self._extract_semantic_facts(chunks)
            if not facts:
                return

            # 批量向量化每条事实
            vector = await self.embedding_client.to_vector(
                [Document(page_content=fact) for fact in facts]
            )

            # 逐条存入 Milvus：按 user_id/repository_id 归置（去 session 绑定，仅留 session_id 作溯源）
            for dense_docs, fact in zip(vector, facts):
                milvus_po = MilvusPO(
                    id=None,
                    user_id=meta["user_id"],
                    repository_id=meta["repository_id"],
                    file_path=meta["file_path"],
                    commit_sha=meta["commit_sha"],
                    branch_name=meta["branch_name"],
                    dense_vector=dense_docs.tolist(),
                    language=None,
                    text=fact,
                    metadata=Metadata(session_id=session_id),
                    content_type="semantic_fact"
                )
                self.milvus_client.insert(milvus_po.to_milvus_dict())

            # 写入“用户+仓库”级存在标记，替代原来的 session 级 has_rag_data 闸门
            await self.redis_client.set(
                self._semantic_flag_key(meta["user_id"], meta["repository_id"]),
                1,
            )

    def _semantic_flag_key(self, user_id: int, repository_id: int) -> str:
        """语义记忆存在性标记键：按 用户+仓库 维度（跨会话），替代 session 级 has_rag_data"""
        return f"semantic:exists:{user_id}:{repository_id}"

    async def _extract_semantic_facts(self, contents: List[str]) -> List[str]:
        """
        从冷对话文本中抽取“脱离本次会话仍然为真、且未来会话值得复用”的稳定事实
        （用户长期偏好、项目技术决策等），丢弃一次性细节与闲聊。
        :param contents: 冷对话原文
        :return: 事实句列表（每行一条）
        """
        prompt = f"""
        你是一个记忆提炼器。请从下面这段对话中，抽取“脱离本次会话后依然成立、且未来会话值得复用”的稳定事实。

        ## 只抽取这类信息
        - 用户的长期偏好（代码风格、语言、工具选择、回答方式等）
        - 项目/仓库层面的既定事实与技术决策（框架、库、架构约定、配置规范）
        - 明确确立的命名、路径、约束等可复用结论

        ## 必须丢弃
        - 仅本次任务相关的临时上下文、一次性细节
        - 具体报错内容、闲聊、情绪化表达
        - 无法脱离本次会话独立成立的信息

        ## 输出要求
        - 每行一条，独立成句、第三人称陈述，不要编号
        - 关键实体（文件路径、函数名、配置项）原样保留，禁止改写或翻译
        - 若没有任何符合条件的稳定事实，只输出一个词：无
        - 不要输出任何解释或前缀

        ## 待提炼对话
        {contents}
        """
        resp = await self.compress_client.ainvoke(prompt)
        text = resp.content if isinstance(resp.content, str) else str(resp.content)
        facts: List[str] = []
        for line in text.splitlines():
            line = line.strip().lstrip("-•* \t")
            if not line or line in ("无", "None"):
                continue
            facts.append(line)
        return facts

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
        # 检索当前仓库的语义记忆信息
        meta = await self._get_session_meta(session_id)
        vector = await self.embedding_client.to_vector(
            [Document(page_content=query)]
        )
        dense_docs = vector[0]
        milvus_messages = await asyncio.to_thread(
            self.milvus_client.retrieval,
            dense_anns_field="dense_vector",
            sparse_anns_field="sparse_vector",
            dense_vector=dense_docs.tolist(),
            sparse_query=query,
            user_id=meta["user_id"],
            repo_id=meta["repository_id"],
            output_fields=["text"],
            # filter_expr=f'metadata["session_id"] == "{session_id}"',
            top_k=5
        )

        messages = []
        for message in milvus_messages:
            for hit in message:
                messages.append(hit.get("text", ""))

        return messages

    async def memory_compress(self, session_id: str, messages: List[str]):
        """
        语义化压缩入向量库（每轮主动沉淀稳定事实，按 user/repo 跨会话）
        :param session_id: 会话ID
        :param messages: 待压缩的消息
        :return: 压缩后的消息
        """
        # 抽取语义事实
        facts = await self._extract_semantic_facts(messages)
        if not facts:
            return

        meta = await self._get_session_meta(session_id)
        flag_key = self._semantic_flag_key(meta["user_id"], meta["repository_id"])

        split_documents = await asyncio.to_thread(
            self.splitter.split_documents,
            [Document(page_content=fact) for fact in facts]
        )

        vector = await self.embedding_client.to_vector(split_documents)

        inserted = False
        dedup_key = f"semantic:fp:{meta['user_id']}:{meta['repository_id']}"
        # 按 split_documents 遍历，text 用 doc.page_content，避免与 vector 错位
        for dense_docs, doc in zip(vector, split_documents):
            fact_text = doc.page_content
            # 指纹去重：同 user/repo 下已沉淀过相同事实则跳过（去空白+小写+md5，精确匹配）
            fp = hashlib.md5("".join(fact_text.split()).lower().encode("utf-8")).hexdigest()
            if await self.redis_client.sismember(dedup_key, fp):
                continue
            milvus_po = MilvusPO(
                id=None,
                user_id=meta["user_id"],
                repository_id=meta["repository_id"],
                file_path=meta["file_path"],
                commit_sha=meta["commit_sha"],
                branch_name=meta["branch_name"],
                dense_vector=dense_docs.tolist(),
                language=None,
                text=fact_text,
                metadata=Metadata(session_id=session_id),
                content_type="semantic_fact"
            )
            self.milvus_client.insert(milvus_po.to_milvus_dict())
            # 插入成功后登记指纹，避免失败的写入被误标记为“已存在”
            await self.redis_client.sadd(dedup_key, fp)
            inserted = True

        # 只有真正写入了事实，才打开跨会话召回闸门
        if inserted:
            await self.redis_client.set(flag_key, 1)

    async def get_history_messages(self, session_id: str, query: str) -> dict:
        """
        获取历史消息
        :param query: 用户消息
        :param session_id: 会话ID
        :return: 消息列表
        """
        meta = await self._get_session_meta(session_id)
        milvus_messages = ""
        # 语义记忆是“用户+仓库”级、跨会话的，闸门不能再依赖 session 级 has_rag_data
        has_semantic = await self.redis_client.get(
            self._semantic_flag_key(meta["user_id"], meta["repository_id"])
        )
        if has_semantic:
            # 只要有该 user/repo 的语义事实，就按 query 跨会话语义召回
            milvus_messages = await self._get_milvus_messages(session_id, query, meta)
        # 消息以 List 类型存储，用 lrange 读取；key 不存在时返回空列表，避免 None 迭代报错
        raw_messages = await self.redis_client.lrange(session_id, 0, -1) # 压缩的会话
        redis_messages = [str(json.loads(raw)) for raw in raw_messages] if raw_messages else "未查询到相关上下文"
        return {
            "milvus_messages": "milvus中检索到的会话上下文（可能为空）：" + str(milvus_messages) if milvus_messages else "",
            "redis_messages": "最近的会话上下文：" + str(redis_messages)
        }

if __name__ == '__main__':
    print(AIMessage(content="Hello, world!"))


import asyncio
import logging
from os.path import join
from typing import List

from dependency_injector.wiring import Provide
from langchain_core.documents import Document

from client import embeding
from config.Container import Container
from core.reranker import Reranker
from core.procedure_store import ProcedureStore
from schema.domain import Context, DiffData
from service.func_service import ArchaeologyService, ExplainService, ReviewService
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel
from jinja2 import Environment, Template

class SearchServiceImpl(ArchaeologyService):
    """
    语义化搜索服务实现
    """

    def __init__(self):
        pass

    async def retrieval(
            self,
            query: str,
            owner: int,
            repo: int,
            context: Context | None = None,
            milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
            embedding_model: EmbeddingModel = Provide[Container.embedding_model],
            **kwargs
    ) -> dict:
        """
        双路召回，RAG检索
        :return: 考古结果

        示例：
        用户：pay 相关的代码是啥？
        Milvus 双路召回
            content_type=code："pay 方法当前代码"
            content_type=commit："修复并发场景 NPE 的提交记录"
        """
        # 向量化，获取向量结果
        vector = await asyncio.to_thread(
            embedding_model.to_vector, [Document(page_content=query)]
        )
        dense_vecs = vector[0]
        result = await asyncio.to_thread(
            milvus_repo.retrieval,
            "dense_vector",
            "sparse_vector",
            user_id=owner,
            repo_id=repo,
            dense_vector=dense_vecs,
            sparse_query=query,
            top_k=50,
            output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
        )

        # 召回结果处理
        recall_docs = []
        for hits in result:
            for hit in hits:
                recall_docs.append({
                    "text": hit.get("text"),
                    "file_path": hit.get("file_path"),
                    "commit_sha": hit.get("commit_sha"),
                    "metadata": hit.get("metadata"),
                    "content_type": hit.get("content_type")
                })

        code_docs = [doc for doc in recall_docs if doc["content_type"] == "code"]
        commit_docs = [doc for doc in recall_docs if doc["content_type"] == "commit_message"]

        return {
            "code_docs": code_docs,
            "commit_docs": commit_docs,
            "total_count": len(recall_docs)
        }

    async def rerank(
            self,
            query: str,
            code_docs: List[dict],
            commit_docs: List[dict],
            top_k: int = 5,
            reranker: Reranker = Provide[Container.reranker]
    ):
        """
        rerank
        :param commit_docs: 提交信息
        :param code_docs: 代码文档
        :param top_k: 重排序结果数量
        :param reranker: 重排序器
        :param query: 查询内容
        :return: rerank结果
        """
        all_docs = code_docs + commit_docs
        total_count = len(all_docs)

        if total_count == 0:
            # 没有召回结果直接返回
            return {
                "code_documents": [],
                "commit_documents": [],
                "total_count": 0
            }

        # 重排序结果
        rerank_result = await asyncio.to_thread(
            reranker.rerank, query, all_docs, text_field="text", top_k=min(total_count, top_k)
        )
        # 按照分数递减排序
        rerank_result.sort(key=lambda x: x["rerank_score"], reverse=True)

        # 重新组装结果
        code_docs = [doc for doc in rerank_result if doc["content_type"] == "code"]
        commit_docs = [doc for doc in rerank_result if doc["content_type"] == "commit_message"]

        return {
            "code_documents": code_docs,
            "commit_documents": commit_docs,
            "total_count": len(rerank_result)
        }

    async def prompt(
            self,
            query: str,
            retrieval_rerank_result: dict,
            owner: int,
            repo: int,
            context: Context | None = None,
            code_search_template: Template = Provide[Container.code_search_template],
            procedure_store: ProcedureStore = Provide[Container.procedure_store],
    ):
        """
        prompt
        :param procedure_store: 程序记忆存储器
        :param context: 上下文
        :param code_search_template: 代码考古模板
        :param query: 查询内容
        :param retrieval_rerank_result: rerank结果
        :param owner: owner
        :param repo: repo
        :return: prompt结果
        """
        code_documents = retrieval_rerank_result.get("code_documents", [])
        commit_documents = retrieval_rerank_result.get("commit_documents", [])

        # 取本意图的 SOP：user > repo > global 逐层覆盖
        procedure = await procedure_store.get("search", user_id=owner, repo_id=repo)

        embedded_to_template = {
            "query": query,
            "code_documents": code_documents,
            "commit_documents": commit_documents,
            "owner": owner,
            "repo": repo,
            "procedure": procedure,
        }

        prompt_result = code_search_template.render(embedded_to_template)
        logging.info(f"prompt: {prompt_result}")

        return prompt_result
import logging
from typing import List

from dependency_injector.wiring import Provide
from langchain_core.documents import Document

from config.Container import Container
from core.reranker import Reranker
from service.func_service import ArchaeologyService
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel
from jinja2 import Environment, Template


class ArchaeologyServiceImpl(ArchaeologyService):
    """
    考古服务实现
    """
    async def retrieval(
            self,
            query: str,
            owner: int,
            repo: int,
            milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
            embedding_model: EmbeddingModel = Provide[Container.embeding_model],
    ) -> dict:
        """
        双路召回，RAG检索
        :return: 考古结果

        示例：
        用户：pay 方法为什么加了判空？
        Milvus 双路召回
            content_type=code："pay 方法当前代码"
            content_type=commit："修复并发场景 NPE 的提交记录"
        """
        # 向量化，获取向量结果
        vector = embedding_model.to_vector([Document(page_content=query)])
        dense_vecs, sparse_vecs = vector[0]
        result = milvus_repo.retrieval(
            "dense_vector",
            "sparse_vector",
            user_id=owner,
            repo_id=repo,
            dense_vector=dense_vecs,
            sparse_vector=sparse_vecs,
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
            "code_documents": code_docs,
            "commit_documents": commit_docs,
            "total_count": len(recall_docs)
        }

    async def rerank(
            self,
            query: str,
            retrieval_result: dict,
            top_k: int = 5,
            reranker: Reranker = Provide[Container.reranker]
    ):
        """
        rerank
        :param top_k: 重排序结果数量
        :param reranker: 重排序器
        :param query: 查询内容
        :param retrieval_result: 召回结果
        :return: rerank结果
        """
        code_docs = retrieval_result.get("code_documents", [])
        commit_docs = retrieval_result.get("commit_documents", [])
        total_count = retrieval_result.get("total_count", 0)
        all_docs = code_docs + commit_docs

        if total_count == 0:
            # 没有召回结果直接返回
            return {
                "code_documents": [],
                "commit_documents": [],
                "total_count": 0
            }

        # 重排序结果
        rerank_result = reranker.rerank(query, all_docs, text_field="text", top_k=min(total_count, top_k))
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
            code_archaeology_template: Template = Provide[Container.code_archaeology_template],
    ):
        """
        prompt
        :param code_archaeology_template: 代码考古模板
        :param query: 查询内容
        :param retrieval_rerank_result: rerank结果
        :param owner: owner
        :param repo: repo
        :return: prompt结果
        """
        code_documents = retrieval_rerank_result.get("code_documents", [])
        commit_documents = retrieval_rerank_result.get("commit_documents", [])

        embedded_to_template = {
            "query": query,
            "code_documents": code_documents,
            "commit_documents": commit_documents,
            "owner": owner,
            "repo": repo
        }

        prompt_result = code_archaeology_template.render(embedded_to_template)
        logging.info(f"prompt: {prompt_result}")

        return prompt_result


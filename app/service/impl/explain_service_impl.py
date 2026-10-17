import asyncio
import logging
from os.path import join
from typing import List

from dependency_injector.wiring import Provide
from langchain_core.documents import Document

from client import embeding
from config.Container import Container
from core.reranker import Reranker
from schema.domain import Context, DiffData
from service.func_service import ArchaeologyService, ExplainService, ReviewService
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel
from jinja2 import Environment, Template

class ExplainServiceImpl(ExplainService):
    """
    解释代码服务实现
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
        两阶段三路召回，RAG检索
        :param context: 上下文
        :param embedding_model: EmbeddingModel
        :param milvus_repo: MilvusRepo
        :param query: 查询内容
        :param owner: owner
        :param repo: repo
        :return: 召回结果

        第一阶段：定位目标代码，提取方法名、类名、文件路径
        第二阶段：
            反向检索：用目标方法名搜索"谁调用了它"
            正向检索：用目标代码内容搜索"它调用了谁"
            同类检索：用文件路径过滤搜索同模块代码
        """
        # ========== 第一阶段：定位目标代码 ==========
        initial_vector = await asyncio.to_thread(
            embedding_model.to_vector, [Document(page_content=query)]
        )
        dense_vecs, sparse_vecs = initial_vector[0]
        initial_result = await asyncio.to_thread(
            milvus_repo.retrieval,
            "dense_vector", "sparse_vector",
            user_id=owner, repo_id=repo,
            dense_vector=dense_vecs, sparse_vector=sparse_vecs,
            top_k=10,
            output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
        )

        # 提取目标代码的元数据
        target_methods = set()
        target_files = set()
        initial_docs = []

        for hits in initial_result:
            for hit in hits:
                doc = {
                    "text": hit.get("text"),
                    "file_path": hit.get("file_path"),
                    "commit_sha": hit.get("commit_sha"),
                    "metadata": hit.get("metadata"),
                    "content_type": hit.get("content_type")
                }
                initial_docs.append(doc)
                target_files.add(hit.get("file_path", ""))

                metadata = hit.get("metadata", {})
                if isinstance(metadata, dict):
                    method_name = metadata.get("method_name")
                    if method_name:
                        target_methods.add(method_name)

        # ========== 第二阶段：构造三路查询并并行向量化 ==========
        # 反向检索查询：用所有提取到的方法名（谁调用了它）
        if target_methods:
            methods_str = "、".join(target_methods)
            reverse_text = f"调用 {methods_str} 方法"
        else:
            reverse_text = f"{query} 调用 引用 caller"

        # 正向检索查询：用目标代码片段（它调用了谁）
        forward_text = f"{query} 依赖 dependency import"
        for doc in initial_docs:
            if doc["content_type"] == "code" and doc["text"]:
                forward_text = f"{doc['text'][:200]} 依赖 调用"
                break

        # 同类检索的文件路径过滤
        file_filter = None
        if target_files:
            target_file = list(target_files)[0]
            parts = target_file.rsplit("/", 1)
            if len(parts) >= 2:
                module_prefix = "/".join(parts[:-1])
                file_filter = f'file_path like "{module_prefix}%"'

        # 三路查询文本
        three_queries = [reverse_text, forward_text]

        # 一次性向量化三路查询（线程池执行，避免阻塞事件循环）
        three_vectors = await asyncio.to_thread(
            embedding_model.to_vector,
            [Document(page_content=q) for q in three_queries]
        )

        # 三路并行检索
        reverse_result, forward_result, similar_result = await asyncio.gather(
            asyncio.to_thread(
                milvus_repo.retrieval,
                "dense_vector", "sparse_vector",
                user_id=owner, repo_id=repo,
                dense_vector=three_vectors[0][0], sparse_vector=three_vectors[0][1],
                top_k=20,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
            ),
            asyncio.to_thread(
                milvus_repo.retrieval,
                "dense_vector", "sparse_vector",
                user_id=owner, repo_id=repo,
                dense_vector=three_vectors[1][0], sparse_vector=three_vectors[1][1],
                top_k=20,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
            ),
            asyncio.to_thread(
                milvus_repo.retrieval,
                "dense_vector", "sparse_vector",
                user_id=owner, repo_id=repo,
                dense_vector=initial_vector[0][0], sparse_vector=initial_vector[0][1],
                top_k=20,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
                filter_expr=file_filter
            ),
        )

        # ========== 合并去重 ==========
        def _flatten(result: list) -> list:
            """将 Milvus 检索结果展平为文档列表"""
            docs = []
            for hits in result:
                for hit in hits:
                    docs.append({
                        "text": hit.get("text"),
                        "file_path": hit.get("file_path"),
                        "commit_sha": hit.get("commit_sha"),
                        "metadata": hit.get("metadata"),
                        "content_type": hit.get("content_type")
                    })
            return docs

        seen = set()
        all_docs = []
        for result in [initial_docs, _flatten(reverse_result), _flatten(forward_result), _flatten(similar_result)]:
            for doc in result:
                dedup_key = (
                    doc.get("file_path", ""),
                    doc.get("commit_sha", ""),
                    doc.get("text", "")[:100]
                )
                if dedup_key not in seen:
                    seen.add(dedup_key)
                    all_docs.append(doc)

        code_docs = [doc for doc in all_docs if doc["content_type"] == "code"]
        commit_docs = [doc for doc in all_docs if doc["content_type"] == "commit_message"]

        return {
            "code_documents": code_docs,
            "commit_documents": commit_docs,
            "total_count": len(all_docs)
        }

    async def rerank(
            self,
            query: str,
            code_docs: List[dict],
            commit_docs: List[dict],
            top_k: int = 10,
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
            review_answer: str,
            context: Context | None = None,
            code_explain_template: Template = Provide[Container.code_explain_template],
    ):
        """
        prompt
        :param context: 上下文
        :param review_answer: 代码审核结果
        :param code_explain_template: 代码解释模板
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
            "repo": repo,
            "review_answer": review_answer
        }

        prompt_result = code_explain_template.render(embedded_to_template)
        logging.info(f"prompt: {prompt_result}")

        return prompt_result
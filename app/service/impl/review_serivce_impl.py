import asyncio
import logging
from typing import List

from dependency_injector.wiring import Provide
from langchain_core.documents import Document

from config.Container import Container
from core.reranker import Reranker
from schema.domain import Context, DiffData
from service.func_service import ArchaeologyService, ExplainService, ReviewService
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel
from jinja2 import Environment, Template


def _normalize_path(path: str) -> str:
    """
    将 Windows 反斜杠路径归一化为 POSIX 正斜杠路径。
    Milvus 中存储的 file_path 使用 '/' 分隔；查询侧必须保持一致，
    否则 rsplit("/")、层级匹配、filter like 前缀全部会失效。
    """
    return path.replace("\\", "/") if path else ""


def _escape_milvus_str(value: str) -> str:
    """
    转义 Milvus filter 表达式字符串字面量中的特殊字符。
    Milvus 使用类 SQL 语法，反斜杠和双引号必须转义，
    否则会抛 'token recognition error' 解析异常。
    """
    if not value:
        return ""
    return value.replace("\\", "\\\\").replace('"', '\\"')


class ReviewServiceImpl(ReviewService):
    """
    代码审核服务实现
    两种模式：
    1.debug模式：对性能，安全性等地方进行审查（仅在代码解释模式下使用）
    2.审查模式：只检查两段代码的diff，并给出当前代码的修改建议，分析影响范围，历史教训匹配，仓库规范一致性检查
    """

    def __init__(self):
        pass

    async def retrieval(
            self,
            query: str,
            owner: int,
            repo: int,
            context: Context | None,
            milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
            embedding_model: EmbeddingModel = Provide[Container.embedding_model],
    ) -> dict:
        """
        双路召回，RAG检索
        :return: 召回结果
        获取影响范围：
        1.反向检索，确认调用方
        2.正向检索，确认被调用放
        3.同级检索，确认同级文件
        历史教训匹配：
        1.文件级历史：召回该文件的所有提交历史
        2.语义级历史：用变更描述做向量检索，召回相似的变更描述
        仓库规范一致性检查：
        1.同模块代码检索：限定file_path
        2.同类型代码检索：直接RAG检索代码
        """
        print("开始执行代码检索")

        # 用diff信息进行检索
        # 获取diff信息
        diff_content = str(context.get("diff_data", query)) if context else query
        # 向量化，获取向量结果
        vector = await asyncio.to_thread(
            embedding_model.to_vector,
            [Document(page_content=diff_content)]
        )

        dense_vecs, sparse_vecs = vector[0]
        result = await asyncio.to_thread(
            milvus_repo.retrieval,
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

        # 归一化 diff_data 中的文件路径（Windows '\' → POSIX '/'），
        # 一次修复，下游 _scope_influence / _history / _repository_consistency 全部生效
        raw_diff_datas = context.get("diff_data", []) if context else []
        diff_datas: List[DiffData] = [
            {**d, "file_path": _normalize_path(d.get("file_path", ""))}
            for d in raw_diff_datas
        ]

        # ==============获取影响范围===========
        influence_docs = await self._scope_influence(
            owner=owner,
            repo=repo,
            diff_datas=diff_datas,
            recall_docs=recall_docs,
            milvus_repo=milvus_repo,
            embedding_model=embedding_model
        )

        # ==============获取历史教训===========
        history_docs = await self._history(
            query=query,
            owner=owner,
            repo=repo,
            diff_datas=diff_datas,
            milvus_repo=milvus_repo,
            embedding_model=embedding_model
        )

        # ===========仓库规范一致性检查===========
        repository_consistency_docs = await self._repository_consistency(
            query=query,
            owner=owner,
            repo=repo,
            diff_datas=diff_datas,
            milvus_repo=milvus_repo,
            embedding_model=embedding_model
        )

        return {
            "influence_docs": influence_docs,
            "history_docs": history_docs,
            "repository_consistency_docs": repository_consistency_docs,
        }

    async def _repository_consistency(
            self,
            query: str,
            owner: int,
            repo: int,
            diff_datas: List[DiffData],
            milvus_repo: MilvusRepo,
            embedding_model: EmbeddingModel,
    ) -> List[dict]:
        """
        仓库规范一致性检查
        :return: 规范参考代码列表
        1. 同模块代码检索：按变更文件的目录前缀过滤 + 向量召回
        2. 同类型代码检索：按层级关键词（controller/service/repository）过滤 + 向量召回
        """
        if not diff_datas:
            return []

        # ========== 1. 提取所有变更文件的模块前缀和层级 ==========
        module_prefixes = set()
        layer_keywords = set()
        LAYERS = ("controller", "service", "repository", "model", "handler", "router", "dao", "impl")

        for diff_data in diff_datas:
            file_path = diff_data.get("file_path", "")
            if not file_path:
                continue

            # 目录前缀：src/service/impl/func_service_impl.py -> src/service/impl/
            parts = file_path.rsplit("/", 1)
            if len(parts) >= 2:
                module_prefixes.add(parts[0] + "/")

            # 层级关键词
            path_lower = file_path.lower()
            for layer in LAYERS:
                if f"/{layer}" in path_lower or path_lower.startswith(f"{layer}"):
                    layer_keywords.add(layer)
                    break

        if not module_prefixes and not layer_keywords:
            return []

        # ========== 2. 构造查询向量（用 diff 摘要，语义更贴近变更内容）==========
        diff_summary = " ".join(
            [d.get("new_content", "")[:300] for d in diff_datas if d.get("new_content")]
        ) or query

        vector = await asyncio.to_thread(
            embedding_model.to_vector,
            [Document(page_content=diff_summary)]
        )
        dense_vecs, sparse_vecs = vector[0]

        # ========== 3. 两路并行检索 ==========
        # 路 A：同模块（目录前缀）
        module_filter = None
        if module_prefixes:
            module_filter = " or ".join(
                [f'file_path like "{_escape_milvus_str(prefix)}%"' for prefix in module_prefixes]
            )
            module_filter = f"({module_filter}) and content_type == \"code\""

        # 路 B：同层级（跨模块找同类型代码）
        layer_filter = None
        if layer_keywords:
            layer_filter = " or ".join(
                [f'file_path like "%/{layer}/%"' for layer in layer_keywords]
            )
            layer_filter = f"({layer_filter}) and content_type == \"code\""

        tasks = []
        if module_filter:
            tasks.append(asyncio.to_thread(
                milvus_repo.retrieval,
                "dense_vector", "sparse_vector",
                user_id=owner, repo_id=repo,
                dense_vector=dense_vecs, sparse_vector=sparse_vecs,
                top_k=15,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
                filter_expr=module_filter,
            ))
        if layer_filter:
            tasks.append(asyncio.to_thread(
                milvus_repo.retrieval,
                "dense_vector", "sparse_vector",
                user_id=owner, repo_id=repo,
                dense_vector=dense_vecs, sparse_vector=sparse_vecs,
                top_k=10,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
                filter_expr=layer_filter,
            ))

        if not tasks:
            return []

        results = await asyncio.gather(*tasks)

        # ========== 4. 合并去重，排除变更文件自身 ==========
        changed_files = {d.get("file_path", "") for d in diff_datas}
        seen = set()
        consistency_docs = []

        for result in results:
            for hits in result:
                for hit in hits:
                    file_path = hit.get("file_path", "")
                    if file_path in changed_files:
                        continue  # 排除自身
                    dedup_key = (file_path, hit.get("text", "")[:100])
                    if dedup_key in seen:
                        continue
                    seen.add(dedup_key)
                    consistency_docs.append({
                        "text": hit.get("text"),
                        "file_path": file_path,
                        "commit_sha": hit.get("commit_sha"),
                        "metadata": hit.get("metadata"),
                        "content_type": hit.get("content_type"),
                    })

        return consistency_docs

    async def _history(
            self,
            query: str,
            owner: int,
            repo: int,
            diff_datas: List[DiffData],
            milvus_repo: MilvusRepo,
            embedding_model: EmbeddingModel,
    ) -> List[dict]:
        """
        获取历史教训
        :return: 历史教训列表
        历史教训匹配：
        1.文件级历史：召回该文件的所有提交历史
        2.语义级历史：用变更描述做向量检索，召回相似的变更描述
        """
        # 1.文件级历史：完整路径用 == 精确匹配（比 like 更准，且避免 % _ 通配符干扰）
        def get_file_path(current_diff_data: DiffData):
            current_file_path = _escape_milvus_str(current_diff_data.get("file_path", ""))
            return f'file_path == "{current_file_path}"'

        file_path = [get_file_path(diff_data) for diff_data in diff_datas]
        file_filers = " or ".join([file_filter for file_filter in file_path])

        # 变更描述做向量检索
        vector = await asyncio.to_thread(
            embedding_model.to_vector,
            [Document(page_content=query)]
        )
        dense_vecs, sparse_vecs = vector[0]

        # 检索
        path_history_result, semantic_history_result = await asyncio.gather(
            # 根据文件路径做标量检索
            asyncio.to_thread(
                milvus_repo.query_retrieval,
                user_id=owner, repo_id=repo,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
                filter_expr=file_filers,
                limit=500,
            ),
            # 查询描述做向量检索
            asyncio.to_thread(
                milvus_repo.retrieval,
                "dense_vector", "sparse_vector",
                user_id=owner, repo_id=repo,
                dense_vector=dense_vecs, sparse_vector=sparse_vecs,
                top_k=20,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
                filter_expr=f"content_type == 'commit_message'"
            ),
        )

        # 处理检索结果，组装成统一格式
        seen = set()
        all_docs = []
        for result in [self._flatten(path_history_result), self._flatten(semantic_history_result)]:
            for doc in result:
                dedup_key = (
                    doc.get("file_path", ""),
                    doc.get("commit_sha", ""),
                    doc.get("text", "")[:100]
                )
                if dedup_key not in seen:
                    seen.add(dedup_key)
                    all_docs.append(doc)

        return all_docs

    async def _scope_influence(
            self,
            owner: int,
            repo: int,
            diff_datas: List[DiffData],
            recall_docs: List[dict],
            milvus_repo: MilvusRepo,
            embedding_model: EmbeddingModel,
    ) -> List[dict]:
        """
        获取影响范围：
        1.反向检索，确认调用方
        2.正向检索，确认被调用方
        3.同级检索，确认同级文件
        """

        # 1.反向检索
        # 获取相关方法
        methods = []
        for recall in recall_docs:
            method_name = recall.get("metadata", {}).get("method_name", None)
            if not method_name or method_name is None:
                continue
            methods.append(method_name)
        # 组装反向查询信息
        reverse_query = ",".join([method for method in methods])

        # 2.正向检索
        # 获取相关调用
        calls = []
        for diff_data in diff_datas:
            old_content = diff_data.get("old_content", "")
            new_content = diff_data.get("new_content", "")
            diff_content = f"old_content: \n {old_content},\n\n new_content: \n{new_content}"
            calls.append(diff_content)

        call_query = ",\n".join([call for call in calls])

        # 3.同级检索
        # 获取同级文件
        def get_file_path(current_diff_data: DiffData):
            parts = current_diff_data.get("file_path", "").rsplit("/", 1)
            if len(parts) >= 2:
                module_prefix = "/".join(parts[:-1])
                file_filter = f'file_path like "{_escape_milvus_str(module_prefix)}%"'
                return file_filter
            return None

        file_path = [get_file_path(diff_data) for diff_data in diff_datas]
        file_filers = " or ".join([file_filter for file_filter in file_path if file_filter is not None])
        three_queries = [reverse_query, call_query, file_filers]
        # 统一向量化
        vector = await asyncio.to_thread(
            embedding_model.to_vector,
            [Document(page_content=q) for q in three_queries]
        )

        # 检索
        reverse_result, forward_result, similar_result = await asyncio.gather(
             asyncio.to_thread(
                 milvus_repo.retrieval,
                "dense_vector","sparse_vector",
                user_id=owner, repo_id=repo,
                dense_vector=vector[0][0], sparse_vector=vector[0][1],
                top_k=20,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
            ),
            asyncio.to_thread(
                milvus_repo.retrieval,
                "dense_vector","sparse_vector",
                user_id=owner, repo_id=repo,
                dense_vector=vector[1][0], sparse_vector=vector[1][1],
                top_k=20,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
            ),
            asyncio.to_thread(
                milvus_repo.retrieval,
                "dense_vector","sparse_vector",
                user_id=owner, repo_id=repo,
                dense_vector=vector[2][0], sparse_vector=vector[2][1],
                top_k=20,
                output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
                filter_expr=file_filers
            )
        )

        # 处理检索结果，组装成统一格式
        seen = set()
        all_docs = []
        for result in [self._flatten(similar_result), self._flatten(reverse_result), self._flatten(forward_result)]:
            for doc in result:
                dedup_key = (
                    doc.get("file_path", ""),
                    doc.get("commit_sha", ""),
                    doc.get("text", "")[:100]
                )
                if dedup_key not in seen:
                    seen.add(dedup_key)
                    all_docs.append(doc)

        # code_docs = [doc for doc in all_docs if doc["content_type"] == "code"]
        # commit_docs = [doc for doc in all_docs if doc["content_type"] == "commit_message"]
        return all_docs

    # ========== 合并去重 ==========
    def _flatten(self, result: list) -> list:
        """将 Milvus 检索结果展平为文档列表"""
        docs = []
        if not result:
            return docs
        
        # 判断结果类型：如果是空列表或第一个元素是字典，则为扁平结构（标量检索）
        # 否则为嵌套结构（向量检索）
        is_flat = not result or isinstance(result[0], dict)
        
        if is_flat:
            # 标量检索结果：List[Dict]
            for hit in result:
                docs.append({
                    "text": hit.get("text"),
                    "file_path": hit.get("file_path"),
                    "commit_sha": hit.get("commit_sha"),
                    "metadata": hit.get("metadata"),
                    "content_type": hit.get("content_type")
                })
        else:
            # 向量检索结果：List[List[Dict]]
            for hits in result:
                for hit in hits:
                    # Milvus hybrid_search 返回的结果中，字段在 entity 内
                    entity = hit.get("entity", hit)
                    docs.append({
                        "text": entity.get("text"),
                        "file_path": entity.get("file_path"),
                        "commit_sha": entity.get("commit_sha"),
                        "metadata": entity.get("metadata"),
                        "content_type": entity.get("content_type")
                    })
        return docs

    async def rerank(
            self,
            query: str,
            docs_name: str,
            docs: List[dict],
            top_k: int = 5,
            reranker: Reranker = Provide[Container.reranker]
    ):
        """
        rerank
        :param docs_name: 文档名称
        :param docs: 召回结果
        :param top_k: 重排序结果数量
        :param reranker: 重排序器
        :param query: 查询内容
        :return: rerank结果
        """
        all_docs = docs
        total_count = len(all_docs)

        if total_count == 0:
            # 没有召回结果直接返回
            return {
                docs_name: [],
                "total_count": 0
            }

        # 重排序结果
        rerank_result = await asyncio.to_thread(
            reranker.rerank, query, all_docs, text_field="text", top_k=min(total_count, top_k)
        )
        # 按照分数递减排序
        rerank_result.sort(key=lambda x: x["rerank_score"], reverse=True)

        return {
            docs_name: rerank_result[:top_k],
        }

    async def prompt(
            self,
            query: str,
            owner: int,
            repo: int,
            retrieval_rerank_result: dict,
            model: int = 2,
            context: Context | None = None,
            code_review_template: Template = Provide[Container.code_review_template],
    ):
        """
        prompt
        :param context:
        :param model: 模式
        :param code_review_template: 代码审查模板
        :param query: 查询内容
        :param retrieval_rerank_result: rerank结果
        :param owner: owner
        :param repo: repo
        :return: prompt结果

          必填: query, owner, repo
          可选: review_model(默认2；1=Debug 快速审查, 2=完整审查), pr_id, pr_name,
        diff_data: List[{file_path, old_content, new_content, diff}],
        influence_docs / history_docs / repository_consistency_docs: List[doc]（rerank 后含 rerank_score）
        """

        pr_id = context.get("pr_id", None) if context is not None else None
        pr_name = context.get("pr_name", None) if context is not None else None
        diff_datas = context.get("diff_data", []) if context is not None else []

        influence_docs = retrieval_rerank_result.get("influence_docs", [])
        history_docs = retrieval_rerank_result.get("history_docs", [])
        repository_consistency_docs = retrieval_rerank_result.get("repository_consistency_docs", [])

        embedded_to_template = {
            "query": query,
            "owner": owner,
            "repo": repo,
            "review_model": model,
            "pr_id": pr_id,
            "pr_name": pr_name,
            "diff_data": diff_datas,
            "influence_docs": influence_docs,
            "history_docs": history_docs,
            "repository_consistency_docs": repository_consistency_docs
        }

        prompt_result = code_review_template.render(embedded_to_template)
        logging.info(f"prompt: {prompt_result}")

        return prompt_result
# import asyncio
# import logging
# from os.path import join
# from typing import List
#
# from dependency_injector.wiring import Provide
# from langchain_core.documents import Document
#
# from client import embeding
# from config.Container import Container
# from core.reranker import Reranker
# from schema.domain import Context, DiffData
# from service.func_service import ArchaeologyService, ExplainService, ReviewService
# from repository.milvus_repo import MilvusRepo
# from client.embeding import EmbeddingModel
# from jinja2 import Environment, Template
#
#
# class ArchaeologyServiceImpl(ArchaeologyService):
#     """
#     考古服务实现
#     """
#
#     def __init__(self):
#         pass
#
#     async def retrieval(
#             self,
#             query: str,
#             owner: int,
#             repo: int,
#             context: Context | None = None,
#             milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
#             embedding_model: EmbeddingModel = Provide[Container.embedding_model],
#             **kwargs
#     ) -> dict:
#         """
#         双路召回，RAG检索
#         :return: 考古结果
#
#         示例：
#         用户：pay 方法为什么加了判空？
#         Milvus 双路召回
#             content_type=code："pay 方法当前代码"
#             content_type=commit："修复并发场景 NPE 的提交记录"
#         """
#         # 向量化，获取向量结果
#         vector = embedding_model.to_vector([Document(page_content=query)])
#         dense_vecs, sparse_vecs = vector[0]
#         result = milvus_repo.retrieval(
#             "dense_vector",
#             "sparse_vector",
#             user_id=owner,
#             repo_id=repo,
#             dense_vector=dense_vecs,
#             sparse_vector=sparse_vecs,
#             top_k=50,
#             output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
#         )
#
#         # 召回结果处理
#         recall_docs = []
#         for hits in result:
#             for hit in hits:
#                 recall_docs.append({
#                     "text": hit.get("text"),
#                     "file_path": hit.get("file_path"),
#                     "commit_sha": hit.get("commit_sha"),
#                     "metadata": hit.get("metadata"),
#                     "content_type": hit.get("content_type")
#                 })
#
#         code_docs = [doc for doc in recall_docs if doc["content_type"] == "code"]
#         commit_docs = [doc for doc in recall_docs if doc["content_type"] == "commit_message"]
#
#         return {
#             "code_documents": code_docs,
#             "commit_documents": commit_docs,
#             "total_count": len(recall_docs)
#         }
#
#     async def rerank(
#             self,
#             query: str,
#             code_docs: List[dict],
#             commit_docs: List[dict],
#             top_k: int = 5,
#             reranker: Reranker = Provide[Container.reranker]
#     ):
#         """
#         rerank
#         :param commit_docs: 提交信息
#         :param code_docs: 代码文档
#         :param top_k: 重排序结果数量
#         :param reranker: 重排序器
#         :param query: 查询内容
#         :return: rerank结果
#         """
#         all_docs = code_docs + commit_docs
#         total_count = len(all_docs)
#
#         if total_count == 0:
#             # 没有召回结果直接返回
#             return {
#                 "code_documents": [],
#                 "commit_documents": [],
#                 "total_count": 0
#             }
#
#         # 重排序结果
#         rerank_result = reranker.rerank(query, all_docs, text_field="text", top_k=min(total_count, top_k))
#         # 按照分数递减排序
#         rerank_result.sort(key=lambda x: x["rerank_score"], reverse=True)
#
#         # 重新组装结果
#         code_docs = [doc for doc in rerank_result if doc["content_type"] == "code"]
#         commit_docs = [doc for doc in rerank_result if doc["content_type"] == "commit_message"]
#
#         return {
#             "code_documents": code_docs,
#             "commit_documents": commit_docs,
#             "total_count": len(rerank_result)
#         }
#
#     async def prompt(
#             self,
#             query: str,
#             retrieval_rerank_result: dict,
#             owner: int,
#             repo: int,
#             code_archaeology_template: Template = Provide[Container.code_archaeology_template],
#     ):
#         """
#         prompt
#         :param code_archaeology_template: 代码考古模板
#         :param query: 查询内容
#         :param retrieval_rerank_result: rerank结果
#         :param owner: owner
#         :param repo: repo
#         :return: prompt结果
#         """
#         code_documents = retrieval_rerank_result.get("code_documents", [])
#         commit_documents = retrieval_rerank_result.get("commit_documents", [])
#
#         embedded_to_template = {
#             "query": query,
#             "code_documents": code_documents,
#             "commit_documents": commit_documents,
#             "owner": owner,
#             "repo": repo
#         }
#
#         prompt_result = code_archaeology_template.render(embedded_to_template)
#         logging.info(f"prompt: {prompt_result}")
#
#         return prompt_result
#
# class ExplainServiceImpl(ExplainService):
#     """
#     解释代码服务实现
#     """
#
#     def __init__(self):
#         pass
#
#     async def retrieval(
#             self,
#             query: str,
#             owner: int,
#             repo: int,
#             context: Context | None = None,
#             milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
#             embedding_model: EmbeddingModel = Provide[Container.embedding_model],
#             **kwargs
#     ) -> dict:
#         """
#         两阶段三路召回，RAG检索
#         :param context: 上下文
#         :param embedding_model: EmbeddingModel
#         :param milvus_repo: MilvusRepo
#         :param query: 查询内容
#         :param owner: owner
#         :param repo: repo
#         :return: 召回结果
#
#         第一阶段：定位目标代码，提取方法名、类名、文件路径
#         第二阶段：
#             反向检索：用目标方法名搜索"谁调用了它"
#             正向检索：用目标代码内容搜索"它调用了谁"
#             同类检索：用文件路径过滤搜索同模块代码
#         """
#         # ========== 第一阶段：定位目标代码 ==========
#         initial_vector = await asyncio.to_thread(
#             embedding_model.to_vector, [Document(page_content=query)]
#         )
#         dense_vecs, sparse_vecs = initial_vector[0]
#         initial_result = await asyncio.to_thread(
#             milvus_repo.retrieval,
#             "dense_vector", "sparse_vector",
#             user_id=owner, repo_id=repo,
#             dense_vector=dense_vecs, sparse_vector=sparse_vecs,
#             top_k=10,
#             output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
#         )
#
#         # 提取目标代码的元数据
#         target_methods = set()
#         target_files = set()
#         initial_docs = []
#
#         for hits in initial_result:
#             for hit in hits:
#                 doc = {
#                     "text": hit.get("text"),
#                     "file_path": hit.get("file_path"),
#                     "commit_sha": hit.get("commit_sha"),
#                     "metadata": hit.get("metadata"),
#                     "content_type": hit.get("content_type")
#                 }
#                 initial_docs.append(doc)
#                 target_files.add(hit.get("file_path", ""))
#
#                 metadata = hit.get("metadata", {})
#                 if isinstance(metadata, dict):
#                     method_name = metadata.get("method_name")
#                     if method_name:
#                         target_methods.add(method_name)
#
#         # ========== 第二阶段：构造三路查询并并行向量化 ==========
#         # 反向检索查询：用所有提取到的方法名（谁调用了它）
#         if target_methods:
#             methods_str = "、".join(target_methods)
#             reverse_text = f"调用 {methods_str} 方法"
#         else:
#             reverse_text = f"{query} 调用 引用 caller"
#
#         # 正向检索查询：用目标代码片段（它调用了谁）
#         forward_text = f"{query} 依赖 dependency import"
#         for doc in initial_docs:
#             if doc["content_type"] == "code" and doc["text"]:
#                 forward_text = f"{doc['text'][:200]} 依赖 调用"
#                 break
#
#         # 同类检索的文件路径过滤
#         file_filter = None
#         if target_files:
#             target_file = list(target_files)[0]
#             parts = target_file.rsplit("/", 1)
#             if len(parts) >= 2:
#                 module_prefix = "/".join(parts[:-1])
#                 file_filter = f'file_path like "{module_prefix}%"'
#
#         # 三路查询文本
#         three_queries = [reverse_text, forward_text]
#
#         # 一次性向量化三路查询（线程池执行，避免阻塞事件循环）
#         three_vectors = await asyncio.to_thread(
#             embedding_model.to_vector,
#             [Document(page_content=q) for q in three_queries]
#         )
#
#         # 三路并行检索
#         reverse_result, forward_result, similar_result = await asyncio.gather(
#             asyncio.to_thread(
#                 milvus_repo.retrieval,
#                 "dense_vector", "sparse_vector",
#                 user_id=owner, repo_id=repo,
#                 dense_vector=three_vectors[0][0], sparse_vector=three_vectors[0][1],
#                 top_k=20,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
#             ),
#             asyncio.to_thread(
#                 milvus_repo.retrieval,
#                 "dense_vector", "sparse_vector",
#                 user_id=owner, repo_id=repo,
#                 dense_vector=three_vectors[1][0], sparse_vector=three_vectors[1][1],
#                 top_k=20,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
#             ),
#             asyncio.to_thread(
#                 milvus_repo.retrieval,
#                 "dense_vector", "sparse_vector",
#                 user_id=owner, repo_id=repo,
#                 dense_vector=initial_vector[0][0], sparse_vector=initial_vector[0][1],
#                 top_k=20,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
#                 filter_expr=file_filter
#             ),
#         )
#
#         # ========== 合并去重 ==========
#         def _flatten(result: list) -> list:
#             """将 Milvus 检索结果展平为文档列表"""
#             docs = []
#             for hits in result:
#                 for hit in hits:
#                     docs.append({
#                         "text": hit.get("text"),
#                         "file_path": hit.get("file_path"),
#                         "commit_sha": hit.get("commit_sha"),
#                         "metadata": hit.get("metadata"),
#                         "content_type": hit.get("content_type")
#                     })
#             return docs
#
#         seen = set()
#         all_docs = []
#         for result in [initial_docs, _flatten(reverse_result), _flatten(forward_result), _flatten(similar_result)]:
#             for doc in result:
#                 dedup_key = (
#                     doc.get("file_path", ""),
#                     doc.get("commit_sha", ""),
#                     doc.get("text", "")[:100]
#                 )
#                 if dedup_key not in seen:
#                     seen.add(dedup_key)
#                     all_docs.append(doc)
#
#         code_docs = [doc for doc in all_docs if doc["content_type"] == "code"]
#         commit_docs = [doc for doc in all_docs if doc["content_type"] == "commit_message"]
#
#         return {
#             "code_documents": code_docs,
#             "commit_documents": commit_docs,
#             "total_count": len(all_docs)
#         }
#
#     async def rerank(
#             self,
#             query: str,
#             code_docs: List[dict],
#             commit_docs: List[dict],
#             top_k: int = 10,
#             reranker: Reranker = Provide[Container.reranker]
#     ):
#         """
#         rerank
#         :param commit_docs: 提交信息
#         :param code_docs: 代码文档
#         :param top_k: 重排序结果数量
#         :param reranker: 重排序器
#         :param query: 查询内容
#         :return: rerank结果
#         """
#         all_docs = code_docs + commit_docs
#         total_count = len(all_docs)
#
#         if total_count == 0:
#             # 没有召回结果直接返回
#             return {
#                 "code_documents": [],
#                 "commit_documents": [],
#                 "total_count": 0
#             }
#
#         # 重排序结果
#         rerank_result = reranker.rerank(query, all_docs, text_field="text", top_k=min(total_count, top_k))
#         # 按照分数递减排序
#         rerank_result.sort(key=lambda x: x["rerank_score"], reverse=True)
#
#         # 重新组装结果
#         code_docs = [doc for doc in rerank_result if doc["content_type"] == "code"]
#         commit_docs = [doc for doc in rerank_result if doc["content_type"] == "commit_message"]
#
#         return {
#             "code_documents": code_docs,
#             "commit_documents": commit_docs,
#             "total_count": len(rerank_result)
#         }
#
#     async def prompt(
#             self,
#             query: str,
#             retrieval_rerank_result: dict,
#             owner: int,
#             repo: int,
#             review_answer: str,
#             code_explain_template: Template = Provide[Container.code_explain_template],
#     ):
#         """
#         prompt
#         :param review_answer: 代码审核结果
#         :param code_explain_template: 代码解释模板
#         :param query: 查询内容
#         :param retrieval_rerank_result: rerank结果
#         :param owner: owner
#         :param repo: repo
#         :return: prompt结果
#         """
#         code_documents = retrieval_rerank_result.get("code_documents", [])
#         commit_documents = retrieval_rerank_result.get("commit_documents", [])
#
#         embedded_to_template = {
#             "query": query,
#             "code_documents": code_documents,
#             "commit_documents": commit_documents,
#             "owner": owner,
#             "repo": repo,
#             "review_answer": review_answer
#         }
#
#         prompt_result = code_explain_template.render(embedded_to_template)
#         logging.info(f"prompt: {prompt_result}")
#
#         return prompt_result
#
# class ReviewServiceImpl(ReviewService):
#     """
#     代码审核服务实现
#     两种模式：
#     1.debug模式：对性能，安全性等地方进行审查（仅在代码解释模式下使用）
#     2.审查模式：只检查两段代码的diff，并给出当前代码的修改建议，分析影响范围，历史教训匹配，仓库规范一致性检查
#     """
#
#     def __init__(self):
#         pass
#
#     async def retrieval(
#             self,
#             query: str,
#             owner: int,
#             repo: int,
#             context: Context | None,
#             milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
#             embedding_model: EmbeddingModel = Provide[Container.embedding_model],
#     ) -> dict:
#         """
#         双路召回，RAG检索
#         :return: 召回结果
#         获取影响范围：
#         1.反向检索，确认调用方
#         2.正向检索，确认被调用放
#         3.同级检索，确认同级文件
#         历史教训匹配：
#         1.文件级历史：召回该文件的所有提交历史
#         2.语义级历史：用变更描述做向量检索，召回相似的变更描述
#         仓库规范一致性检查：
#         1.同模块代码检索：限定file_path
#         2.同类型代码检索：直接RAG检索代码
#         """
#
#         # 用diff信息进行检索
#         # 获取diff信息
#         diff_content = str(context.get("diff_data", query)) if context else query
#         # 向量化，获取向量结果
#         vector = asyncio.to_thread(
#             embedding_model.to_vector,
#             [Document(page_content=diff_content)]
#         )
#
#         dense_vecs, sparse_vecs = vector[0]
#         result = milvus_repo.retrieval(
#             "dense_vector",
#             "sparse_vector",
#             user_id=owner,
#             repo_id=repo,
#             dense_vector=dense_vecs,
#             sparse_vector=sparse_vecs,
#             top_k=50,
#             output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
#         )
#
#         # 召回结果处理
#         recall_docs = []
#         for hits in result:
#             for hit in hits:
#                 recall_docs.append({
#                     "text": hit.get("text"),
#                     "file_path": hit.get("file_path"),
#                     "commit_sha": hit.get("commit_sha"),
#                     "metadata": hit.get("metadata"),
#                     "content_type": hit.get("content_type")
#                 })
#
#         # ==============获取影响范围===========
#         influence_docs = await self._scope_influence(
#             owner=owner,
#             repo=repo,
#             diff_datas=context.get("diff_data", []), # context 不会等于None
#             recall_docs=recall_docs,
#             milvus_repo=milvus_repo,
#             embedding_model=embedding_model
#         )
#
#         # ==============获取历史教训===========
#         history_docs = await self._history(
#             query=query,
#             owner=owner,
#             repo=repo,
#             diff_datas=context.get("diff_data", []),
#             milvus_repo=milvus_repo,
#             embedding_model=embedding_model
#         )
#
#         # ===========仓库规范一致性检查===========
#         repository_consistency_docs = await self._repository_consistency(
#             query=query,
#             owner=owner,
#             repo=repo,
#             diff_datas=context.get("diff_data", []),
#             milvus_repo=milvus_repo,
#             embedding_model=embedding_model
#         )
#
#
#
#
#
#
#
#
#
#         code_docs = [doc for doc in recall_docs if doc["content_type"] == "code"]
#         commit_docs = [doc for doc in recall_docs if doc["content_type"] == "commit_message"]
#
#         return {
#             "code_documents": code_docs,
#             "commit_documents": commit_docs,
#             "total_count": len(recall_docs)
#         }
#
#     async def _repository_consistency(
#             self,
#             query: str,
#             owner: int,
#             repo: int,
#             diff_datas: List[DiffData],
#             milvus_repo: MilvusRepo,
#             embedding_model: EmbeddingModel,
#     ) -> List[dict]:
#         """
#         仓库规范一致性检查
#         :return: 规范参考代码列表
#         1. 同模块代码检索：按变更文件的目录前缀过滤 + 向量召回
#         2. 同类型代码检索：按层级关键词（controller/service/repository）过滤 + 向量召回
#         """
#         if not diff_datas:
#             return []
#
#         # ========== 1. 提取所有变更文件的模块前缀和层级 ==========
#         module_prefixes = set()
#         layer_keywords = set()
#         LAYERS = ("controller", "service", "repository", "model", "handler", "router", "dao", "impl")
#
#         for diff_data in diff_datas:
#             file_path = diff_data.get("file_path", "")
#             if not file_path:
#                 continue
#
#             # 目录前缀：src/service/impl/func_service_impl.py -> src/service/impl/
#             parts = file_path.rsplit("/", 1)
#             if len(parts) >= 2:
#                 module_prefixes.add(parts[0] + "/")
#
#             # 层级关键词
#             path_lower = file_path.lower()
#             for layer in LAYERS:
#                 if f"/{layer}" in path_lower or path_lower.startswith(f"{layer}"):
#                     layer_keywords.add(layer)
#                     break
#
#         if not module_prefixes and not layer_keywords:
#             return []
#
#         # ========== 2. 构造查询向量（用 diff 摘要，语义更贴近变更内容）==========
#         diff_summary = " ".join(
#             [d.get("new_content", "")[:300] for d in diff_datas if d.get("new_content")]
#         ) or query
#
#         vector = await asyncio.to_thread(
#             embedding_model.to_vector,
#             [Document(page_content=diff_summary)]
#         )
#         dense_vecs, sparse_vecs = vector[0]
#
#         # ========== 3. 两路并行检索 ==========
#         # 路 A：同模块（目录前缀）
#         module_filter = None
#         if module_prefixes:
#             module_filter = " or ".join(
#                 [f'file_path like "{prefix}%"' for prefix in module_prefixes]
#             )
#             module_filter = f"({module_filter}) and content_type == \"code\""
#
#         # 路 B：同层级（跨模块找同类型代码）
#         layer_filter = None
#         if layer_keywords:
#             layer_filter = " or ".join(
#                 [f'file_path like "%/{layer}/%"' for layer in layer_keywords]
#             )
#             layer_filter = f"({layer_filter}) and content_type == \"code\""
#
#         tasks = []
#         if module_filter:
#             tasks.append(asyncio.to_thread(
#                 milvus_repo.retrieval,
#                 "dense_vector", "sparse_vector",
#                 user_id=owner, repo_id=repo,
#                 dense_vector=dense_vecs, sparse_vector=sparse_vecs,
#                 top_k=15,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
#                 filter_expr=module_filter,
#             ))
#         if layer_filter:
#             tasks.append(asyncio.to_thread(
#                 milvus_repo.retrieval,
#                 "dense_vector", "sparse_vector",
#                 user_id=owner, repo_id=repo,
#                 dense_vector=dense_vecs, sparse_vector=sparse_vecs,
#                 top_k=10,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
#                 filter_expr=layer_filter,
#             ))
#
#         if not tasks:
#             return []
#
#         results = await asyncio.gather(*tasks)
#
#         # ========== 4. 合并去重，排除变更文件自身 ==========
#         changed_files = {d.get("file_path", "") for d in diff_datas}
#         seen = set()
#         consistency_docs = []
#
#         for result in results:
#             for hits in result:
#                 for hit in hits:
#                     file_path = hit.get("file_path", "")
#                     if file_path in changed_files:
#                         continue  # 排除自身
#                     dedup_key = (file_path, hit.get("text", "")[:100])
#                     if dedup_key in seen:
#                         continue
#                     seen.add(dedup_key)
#                     consistency_docs.append({
#                         "text": hit.get("text"),
#                         "file_path": file_path,
#                         "commit_sha": hit.get("commit_sha"),
#                         "metadata": hit.get("metadata"),
#                         "content_type": hit.get("content_type"),
#                     })
#
#         return consistency_docs
#
#     async def _history(
#             self,
#             query: str,
#             owner: int,
#             repo: int,
#             diff_datas: List[DiffData],
#             milvus_repo: MilvusRepo,
#             embedding_model: EmbeddingModel,
#     ) -> List[dict]:
#         """
#         获取历史教训
#         :return: 历史教训列表
#         历史教训匹配：
#         1.文件级历史：召回该文件的所有提交历史
#         2.语义级历史：用变更描述做向量检索，召回相似的变更描述
#         """
#         # 1.文件级历史
#         # 获取文件路径
#         def get_file_path(current_diff_data: DiffData):
#             current_file_path = current_diff_data.get("file_path", "")
#             return f'file_path like "{current_file_path}"'
#
#         file_path = [get_file_path(diff_data) for diff_data in diff_datas]
#         file_filers = " or ".join([file_filter for file_filter in file_path])
#
#         # 变更描述做向量检索
#         vector = await asyncio.to_thread(
#             embedding_model.to_vector,
#             [Document(page_content=query)]
#         )
#         dense_vecs, sparse_vecs = vector[0]
#
#         # 检索
#         path_history_result, semantic_history_result = await asyncio.gather(
#             # 根据文件路径做标量检索
#             asyncio.to_thread(
#                 milvus_repo.query_retrieval,
#                 user_id=owner, repo_id=repo,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
#                 filter_expr=file_filers,
#                 limit=500,
#             ),
#             # 查询描述做向量检索
#             asyncio.to_thread(
#                 milvus_repo.retrieval,
#                 "dense_vector", "sparse_vector",
#                 user_id=owner, repo_id=repo,
#                 dense_vector=dense_vecs, sparse_vector=sparse_vecs,
#                 top_k=20,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
#                 filter_expr=f"content_type == 'commit_message'"
#             ),
#         )
#
#         # 处理检索结果，组装成统一格式
#         seen = set()
#         all_docs = []
#         for result in [self._flatten(path_history_result), self._flatten(semantic_history_result)]:
#             for doc in result:
#                 dedup_key = (
#                     doc.get("file_path", ""),
#                     doc.get("commit_sha", ""),
#                     doc.get("text", "")[:100]
#                 )
#                 if dedup_key not in seen:
#                     seen.add(dedup_key)
#                     all_docs.append(doc)
#
#         return all_docs
#
#     async def _scope_influence(
#             self,
#             owner: int,
#             repo: int,
#             diff_datas: List[DiffData],
#             recall_docs: List[dict],
#             milvus_repo: MilvusRepo,
#             embedding_model: EmbeddingModel,
#     ) -> List[dict]:
#         """
#         获取影响范围：
#         1.反向检索，确认调用方
#         2.正向检索，确认被调用方
#         3.同级检索，确认同级文件
#         """
#
#         # 1.反向检索
#         # 获取相关方法
#         methods = []
#         for recall in recall_docs:
#             method_name = recall.get("metadata", {}).get("method_name", None)
#             if not method_name or method_name is None:
#                 continue
#             methods.append(method_name)
#         # 组装反向查询信息
#         reverse_query = ",".join([method for method in methods])
#
#         # 2.正向检索
#         # 获取相关调用
#         calls = []
#         for diff_data in diff_datas:
#             old_content = diff_data.get("old_content", "")
#             new_content = diff_data.get("new_content", "")
#             diff_content = f"old_content: \n {old_content},\n\n new_content: \n{new_content}"
#             calls.append(diff_content)
#
#         call_query = ",\n".join([call for call in calls])
#
#         # 3.同级检索
#         # 获取同级文件
#         def get_file_path(current_diff_data: DiffData):
#             parts = current_diff_data.get("file_path", "").rsplit("/", 1)
#             if len(parts) >= 2:
#                 module_prefix = "/".join(parts[:-1])
#                 file_filter = f'file_path like "{module_prefix}%"'
#                 return file_filter
#             return None
#
#         file_path = [get_file_path(diff_data) for diff_data in diff_datas]
#         file_filers = " or ".join([file_filter for file_filter in file_path if file_filter is not None])
#         three_queries = [reverse_query, call_query, file_filers]
#         # 统一向量化
#         vector = await asyncio.to_thread(
#             embedding_model.to_vector,
#             [Document(page_content=q) for q in three_queries]
#         )
#
#         # 检索
#         reverse_result, forward_result, similar_result = await asyncio.gather(
#              asyncio.to_thread(
#                  milvus_repo.retrieval,
#                 "dense_vector","sparse_vector",
#                 user_id=owner, repo_id=repo,
#                 dense_vector=vector[0][0], sparse_vector=vector[0][1],
#                 top_k=20,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
#             ),
#             asyncio.to_thread(
#                 milvus_repo.retrieval,
#                 "dense_vector","sparse_vector",
#                 user_id=owner, repo_id=repo,
#                 dense_vector=vector[1][0], sparse_vector=vector[1][1],
#                 top_k=20,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"]
#             ),
#             asyncio.to_thread(
#                 milvus_repo.retrieval,
#                 "dense_vector","sparse_vector",
#                 user_id=owner, repo_id=repo,
#                 dense_vector=vector[2][0], sparse_vector=vector[2][1],
#                 top_k=20,
#                 output_fields=["text", "file_path", "commit_sha", "metadata", "content_type"],
#                 filter_expr=file_filers
#             )
#         )
#
#         # 处理检索结果，组装成统一格式
#         seen = set()
#         all_docs = []
#         for result in [self._flatten(similar_result), self._flatten(reverse_result), self._flatten(forward_result)]:
#             for doc in result:
#                 dedup_key = (
#                     doc.get("file_path", ""),
#                     doc.get("commit_sha", ""),
#                     doc.get("text", "")[:100]
#                 )
#                 if dedup_key not in seen:
#                     seen.add(dedup_key)
#                     all_docs.append(doc)
#
#         # code_docs = [doc for doc in all_docs if doc["content_type"] == "code"]
#         # commit_docs = [doc for doc in all_docs if doc["content_type"] == "commit_message"]
#         return all_docs
#
#     def _flatten(self, result: list) -> list:
#         # ========== 合并去重 ==========
#         """将 Milvus 检索结果展平为文档列表"""
#         docs = []
#         for hits in result:
#             for hit in hits:
#                 docs.append({
#                     "text": hit.get("text"),
#                     "file_path": hit.get("file_path"),
#                     "commit_sha": hit.get("commit_sha"),
#                     "metadata": hit.get("metadata"),
#                     "content_type": hit.get("content_type")
#                 })
#         return docs
#
#     async def rerank(
#             self,
#             query: str,
#             code_docs: List[dict],
#             commit_docs: List[dict],
#             top_k: int = 5,
#             reranker: Reranker = Provide[Container.reranker]
#     ):
#         """
#         rerank
#         :param commit_docs: 提交信息
#         :param code_docs: 代码文档
#         :param top_k: 重排序结果数量
#         :param reranker: 重排序器
#         :param query: 查询内容
#         :return: rerank结果
#         """
#         all_docs = code_docs + commit_docs
#         total_count = len(all_docs)
#
#         if total_count == 0:
#             # 没有召回结果直接返回
#             return {
#                 "code_documents": [],
#                 "commit_documents": [],
#                 "total_count": 0
#             }
#
#         # 重排序结果
#         rerank_result = reranker.rerank(query, all_docs, text_field="text", top_k=min(total_count, top_k))
#         # 按照分数递减排序
#         rerank_result.sort(key=lambda x: x["rerank_score"], reverse=True)
#
#         # 重新组装结果
#         code_docs = [doc for doc in rerank_result if doc["content_type"] == "code"]
#         commit_docs = [doc for doc in rerank_result if doc["content_type"] == "commit_message"]
#
#         return {
#             "code_documents": code_docs,
#             "commit_documents": commit_docs,
#             "total_count": len(rerank_result)
#         }
#
#     async def prompt(
#             self,
#             query: str,
#             owner: int,
#             repo: int,
#             model: int,
#             retrieval_rerank_result: dict,
#             code_review_template: Template = Provide[Container.code_review_template],
#             **kwargs,
#     ):
#         """
#         prompt
#         :param model: 模式
#         :param code_review_template: 代码审查模板
#         :param query: 查询内容
#         :param retrieval_rerank_result: rerank结果
#         :param owner: owner
#         :param repo: repo
#         :return: prompt结果
#         """
#         code_documents = retrieval_rerank_result.get("code_documents", [])
#         commit_documents = retrieval_rerank_result.get("commit_documents", [])
#
#         embedded_to_template = {
#             "query": query,
#             "code_documents": code_documents,
#             "commit_documents": commit_documents,
#             "owner": owner,
#             "repo": repo,
#             "review_model": model,
#             **kwargs
#         }
#
#         prompt_result = code_review_template.render(embedded_to_template)
#         logging.info(f"prompt: {prompt_result}")
#
#         return prompt_result
#

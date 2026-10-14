from dependency_injector.wiring import Provide
from langchain_core.documents import Document

from config.Container import Container
from service.func_service import ArchaeologyService
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel

class ArchaeologyServiceImpl(ArchaeologyService):
    """
    考古服务实现
    """
    async def archaeology(
            self,
            query: str,
            owner: int,
            repo: int,
            milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
            embedding_model: EmbeddingModel = Provide[Container.embeding_model],
    ) -> dict:
        """
        双路召回
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

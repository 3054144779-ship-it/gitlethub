"""
向量化工具：调用 inference 服务获取稠密向量

稀疏向量不再由客户端生成，改由 Milvus 的 BM25 Function 在写入时根据 text 自动生成。
"""
from typing import List

import httpx
import numpy as np
from langchain_core.documents import Document


class EmbeddingModel:
    """向量化模型（远程 API）"""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self._client = httpx.Client(timeout=120)

    def to_vector(self, documents: List[Document]) -> List[np.ndarray]:
        """
        将文档列表转换为稠密向量
        :param documents: 文档列表
        :return: 每篇文档对应的稠密向量
        """
        if not documents:
            return []

        data = self._client.post(
            url=f"{self.base_url}/model/embeddings",
            json={"texts": [doc.page_content for doc in documents]},
            headers={"Content-Type": "application/json"},
        )
        data.raise_for_status()

        result = data.json()
        dense_vecs = result.get("dense_vecs", [])
        if len(dense_vecs) != len(documents):
            raise ValueError(
                f"embedding 数量不匹配: 请求 {len(documents)} 条, 返回 {len(dense_vecs)} 条"
            )

        return [np.array(dense, dtype=np.float32) for dense in dense_vecs]

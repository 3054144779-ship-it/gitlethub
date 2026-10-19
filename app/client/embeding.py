"""
向量化工具
"""
import threading

import httpx
import torch
import logging
import numpy as np
from typing import List

from config.Settings import Settings
from FlagEmbedding import BGEM3FlagModel
from langchain_core.documents import Document
from repository.milvus_repo import MilvusRepo


class EmbeddingModel:
    """向量化模型"""

    # def __init__(self, model_name_or_path: str = "model/bge-m3", device: str = "cuda"):
    #     if device == "cuda" and not torch.cuda.is_available():
    #         logging.warning("CUDA 无法使用, 降级为 CPU")
    #         device = "cpu"
    #     self.embedding_model = BGEM3FlagModel(model_name_or_path=model_name_or_path, device=device)
    #     self._lock = threading.Lock()

    def __init__(self, base_url: str):  # 不再本地加载模型
        self.base_url = base_url
        self._client = httpx.Client(timeout=30)

    def to_vector(self, documents: List[Document]) -> List[tuple]:
        """
        将文档列表转换为向量，返回密集向量和稀疏向量
        :param documents: 文档列表
        :return: 密集向量和稀疏向量
        """

        if not documents:
            return []

        # fast tokenizer 非线程安全，加锁避免并发 "Already borrowed"
        # with self._lock:
        #     result = self.embedding_model.encode([doc.page_content for doc in documents], return_dense=True, return_sparse=True)
        data = self._client.post(
            url=f"{self.base_url}/model/embeddings",
            json={"texts": [d.page_content for d in documents]},
            headers={"Content-Type": "application/json"},
        )

        # 抛出异常
        data.raise_for_status()

        result = data.json()
        dense_vecs = result.get("dense_vecs", [])
        sparse_vecs = result.get("lexical_weights", [])

        embeddings = []
        for dense, sparse in zip(dense_vecs, sparse_vecs):
            if isinstance(dense, torch.Tensor):
                dense = dense.cpu().numpy().astype(np.float32)
            else:
                dense = np.array(dense, dtype=np.float32)

            if isinstance(sparse, dict):
                sparse = {int(k) : float(v) for k, v in sparse.items()}
            else:
                raise ValueError("Invalid sparse vector format")

            embeddings.append((dense, sparse))

        return embeddings


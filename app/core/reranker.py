"""
重排序工具
"""

import logging
from typing import List

from FlagEmbedding import FlagReranker

class Reranker:
    def __init__(
            self,
            model_name_or_path: str = "BAAI/bge-reranker-v2-m3",
            use_fp16: bool = False, # 是否使用半精度
            device: str = "cuda", # 设备
    ):

        self.reranker = FlagReranker(
            model_name_or_path=model_name_or_path,
            device=device,
            use_fp16=use_fp16,
        )

    def rerank(
            self,
            query: str,
            documents: List[dict],
            top_k: int = 10,
            text_field: str = "text",
            batch_size: int = 32,
            normalize: bool = True,
    ) -> List[dict]:
        """
        重排序
        :param documents:文档列表
        :param normalize: 是否归一化
        :param batch_size: 批处理大小
        :param text_field: 文本字段
        :param top_k: 重排序结果数量
        :param query: 查询内容
        :return: 重排序结果
        """

        if not documents:
            return []

        # 组装查询对，用户问题+召回结果
        texts = [doc[text_field] for doc in documents]
        pairs = [(query, text) for text in texts]

        scores = self.reranker.compute_score(
            pairs,
            batch_size=batch_size, # 批处理大小
            normalize=normalize, # 是否归一化
        )

        if isinstance(scores, float):
            # 处理单个分数的情况
            scores = [scores]

        # 添加分数
        for score, doc in zip(scores, documents):
            doc["rerank_score"] = score

        return documents[:top_k]


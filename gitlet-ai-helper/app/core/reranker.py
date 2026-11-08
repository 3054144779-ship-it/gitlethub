"""
重排序工具
"""

import logging
from typing import List

import httpx

class Reranker:
    # def __init__(
    #         self,
    #         model_name_or_path: str = "BAAI/bge-reranker-v2-m3",
    #         use_fp16: bool = False, # 是否使用半精度
    #         device: str = "cuda", # 设备
    # ):
    #     """
    #     初始化重排序器
    #     :param model_name_or_path: 模型名称或路径
    #     :param use_fp16: 是否使用半精度
    #     :param device: 设备
    #     """
    #     if device == "cuda" and not torch.cuda.is_available():
    #         logging.warning("CUDA 无法使用, 降级为 CPU")
    #         device = "cpu"
    #     self.reranker = FlagReranker(
    #         model_name_or_path=model_name_or_path,
    #         device=device,
    #         use_fp16=use_fp16,
    #     )
    #     self._lock = threading.Lock()

    def __init__(self, base_url: str):  # 不再本地加载模型
        self.base_url = base_url.rstrip("/")
        self._client = httpx.AsyncClient(timeout=120)

    async def rerank(
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

        # fast tokenizer 非线程安全，加锁避免并发 "Already borrowed"
        # with self._lock:
        #     scores = self.reranker.compute_score(
        #         pairs,
        #         batch_size=batch_size,  # 批处理大小
        #         normalize=normalize,  # 是否归一化
        #     )

        json_incoming = {
            "pairs": pairs,
            "top_k": top_k,
            "text_field": text_field,
            "batch_size": batch_size,
            "normalize": normalize,
        }

        data = await self._client.post(
            url=f"{self.base_url}/model/rerank",
            json=json_incoming,
            headers={"Content-Type": "application/json"},
        )

        data.raise_for_status()

        rerank_result = data.json()
        scores = rerank_result.get("scores")

        if not scores:
            logging.warning("rerank 未返回有效 scores，跳过重排序: %s", rerank_result)
            for doc in documents:
                doc["rerank_score"] = 0
            return documents[:top_k]

        if isinstance(scores, float):
            # 处理单个分数的情况
            scores = [scores]

        # 添加分数
        for score, doc in zip(scores, documents):
            doc["rerank_score"] = score

        return documents[:top_k]

    async def close_client(self):
        await self._client.aclose()

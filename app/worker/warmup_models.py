"""
模型预热

在应用启动阶段主动触发各本地模型的 dummy 推理，把"懒加载权重 + 首次推理"
的冷启动成本前置到启动期，避免第一个真实请求承担全部开销。
"""
import logging
import time

from langchain_core.documents import Document

from config.Container import Container

logger = logging.getLogger(__name__)

# 预热用 dummy 文本，覆盖中英文 + 代码片段，尽量触发完整的分词/编码路径
_DUMMY_TEXT = "模型预热 warmup: public void blacklist(String authorization) { return; }"


def warmup_models(container: Container) -> None:
    """依次预热本地模型；任一模型预热失败都不应阻断应用启动。"""

    # 1) Embedding 模型 (bge-m3)：实例化会加载权重，to_vector 触发首次编码推理
    _run(
        "EmbeddingModel",
        lambda: container.embedding_model().to_vector(
            [Document(page_content=_DUMMY_TEXT)]
        ),
    )

    # 2) Reranker 模型 (bge-reranker-v2-m3)：documents 必须非空才会真正 compute_score
    _run(
        "Reranker",
        lambda: container.reranker().rerank(
            query=_DUMMY_TEXT,
            documents=[{"text": _DUMMY_TEXT}],
            top_k=1,
        ),
    )


def _run(name: str, fn) -> None:
    start = time.perf_counter()
    try:
        fn()
        logger.info(f"[warmup] {name} 预热完成，耗时 {time.perf_counter() - start:.2f}s")
    except Exception as e:
        logger.error(f"[warmup] {name} 预热失败: {e}")
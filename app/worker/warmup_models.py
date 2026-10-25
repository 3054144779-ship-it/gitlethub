"""
模型连通性预热

Embedding / Reranker 已改为调用 inference 服务（其背后是 LiteLLM + 云端 API），
这里在启动阶段发一次 dummy 请求，提前暴露网络、鉴权或上游配额问题；
失败只记录日志，不阻断应用启动。
"""
import logging
import time

from langchain_core.documents import Document

from config.Container import Container

logger = logging.getLogger(__name__)

# 预热用 dummy 文本，覆盖中英文 + 代码片段，尽量触发完整的分词/编码路径
_DUMMY_TEXT = "模型预热 warmup: public void blacklist(String authorization) { return; }"


def warmup_models(container: Container) -> None:
    """依次探测远端模型服务；任一路失败都不应阻断应用启动。"""

    # 1) Embedding：验证 inference -> LiteLLM -> 云端 embedding 链路
    _run(
        "EmbeddingModel",
        lambda: container.embedding_model().to_vector(
            [Document(page_content=_DUMMY_TEXT)]
        ),
    )

    # 2) Reranker：documents 必须非空，否则不会真正发起请求
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
        logger.info(f"[warmup] {name} 连通性检查通过，耗时 {time.perf_counter() - start:.2f}s")
    except Exception as e:
        logger.error(f"[warmup] {name} 连通性检查失败: {e}")
import logging
import os
from pathlib import Path

import httpx
from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[2] / ".env")
from langchain.embeddings import init_embeddings

logger = logging.getLogger("model")

# 统一出口：LiteLLM 网关
LITELLM_BASE = os.getenv("LITELLM_BASE", "http://127.0.0.1:4000/v1")
LITELLM_KEY = os.getenv("LITELLM_KEY") or os.getenv("LITELLM_MASTER_KEY", "")
EMBED_MODEL = os.getenv("EMBED_MODEL", "BAAI/bge-m3")
RERANK_MODEL = os.getenv("RERANK_MODEL", "BAAI/bge-reranker-v2-m3")
API_TIMEOUT = float(os.getenv("API_TIMEOUT", "120"))

# BGE-M3 单条输入上限 8192 token，超了 SiliconFlow 返回 20015。
# 按字符切到 2000（全中文最坏约 2000 token）留足余量；分片过多时截断，避免放大请求数。
MAX_EMBED_CHARS = int(os.getenv("MAX_EMBED_CHARS", "2000"))
MAX_EMBED_PIECES = int(os.getenv("MAX_EMBED_PIECES", "32"))

embedding_model = init_embeddings(
    model=f"openai:{EMBED_MODEL}",
    api_key=LITELLM_KEY,
    base_url=LITELLM_BASE,
    # 非 OpenAI 官方端点必须关，否则会把文本转 token id 再发
    check_embedding_ctx_length=False,
    chunk_size=int(os.getenv("EMBED_BATCH_SIZE", "32")),
)


def _split_text(text: str) -> list[str]:
    if len(text) <= MAX_EMBED_CHARS:
        return [text]
    return [
        text[i:i + MAX_EMBED_CHARS]
        for i in range(0, len(text), MAX_EMBED_CHARS)
    ][:MAX_EMBED_PIECES]


def _normalize(vector: list[float]) -> list[float]:
    norm = sum(value * value for value in vector) ** 0.5
    return [value / norm for value in vector] if norm else vector


def embed_documents(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []

    overlong = [len(text) for text in texts if len(text) > MAX_EMBED_CHARS]
    if overlong:
        logger.warning(
            "检测到 %d 条超长文本（最长 %d 字符），按 %d 字符切片后均值池化",
            len(overlong), max(overlong), MAX_EMBED_CHARS,
        )
        if max(overlong) > MAX_EMBED_CHARS * MAX_EMBED_PIECES:
            logger.warning(
                "超过 %d 字符的部分已丢弃（MAX_EMBED_PIECES=%d）",
                MAX_EMBED_CHARS * MAX_EMBED_PIECES, MAX_EMBED_PIECES,
            )

    pieces: list[str] = []
    owners: list[int] = []
    for index, text in enumerate(texts):
        for piece in _split_text(text):
            pieces.append(piece)
            owners.append(index)

    vectors = embedding_model.embed_documents(pieces)
    if len(pieces) == len(texts):
        return vectors

    # 分片向量按原始输入分组求均值，再归一化：BGE-M3 输出是归一化的，平均后必须补回单位长度
    sums = [[0.0] * len(vectors[0]) for _ in texts]
    counts = [0] * len(texts)
    for owner, vector in zip(owners, vectors):
        accumulator = sums[owner]
        for dimension, value in enumerate(vector):
            accumulator[dimension] += value
        counts[owner] += 1

    return [
        _normalize([value / counts[index] for value in sums[index]])
        for index in range(len(texts))
    ]


class RemoteModelError(RuntimeError):
    pass


async def rerank_group(query: str, documents: list[str]) -> list[float]:
    if not documents:
        return []
    async with httpx.AsyncClient(timeout=API_TIMEOUT) as client:
        resp = await client.post(
            f"{LITELLM_BASE}/rerank",
            headers={
                "Authorization": f"Bearer {LITELLM_KEY}",
                "Content-Type": "application/json",
            },
            json={"model": RERANK_MODEL, "query": query, "documents": documents},
        )
    if resp.status_code != 200:
        raise RemoteModelError(f"rerank {resp.status_code}: {resp.text[:300]}")

    scores = [0.0] * len(documents)
    for item in resp.json().get("results", []):
        idx = item.get("index")
        if isinstance(idx, int) and 0 <= idx < len(scores):
            scores[idx] = float(item.get("relevance_score", 0.0))
    return scores
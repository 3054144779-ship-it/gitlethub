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

embedding_model = init_embeddings(
    model=f"openai:{EMBED_MODEL}",
    api_key=LITELLM_KEY,
    base_url=LITELLM_BASE,
    # 非 OpenAI 官方端点必须关，否则会把文本转 token id 再发
    check_embedding_ctx_length=False,
    chunk_size=int(os.getenv("EMBED_BATCH_SIZE", "32")),
)


def embed_documents(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []
    return embedding_model.embed_documents(texts)


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
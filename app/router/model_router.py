import logging

from fastapi import APIRouter, HTTPException

from schema.Request import EmbedRequest, RerankRequest
from service.models import embed_documents, rerank_group
from redis.asyncio import Redis


logger = logging.getLogger("model")

router = APIRouter(prefix="/model")


@router.post("/embeddings")
def embeddings(req: EmbedRequest):
    """稠密向量由 LiteLLM 网关转给 SiliconFlow，不再本地加载 BGE-M3。"""
    if not req.texts:
        return {"dense_vecs": []}
    try:
        dense_vecs = embed_documents(req.texts)
    except Exception as e:
        logger.exception("embedding 调用失败")
        raise HTTPException(status_code=502, detail=f"embedding failed: {e}") from e
    return {"dense_vecs": dense_vecs}


@router.post("/rerank")
async def rerank(req: RerankRequest):
    """所有 pair 共用同一个 query，按原顺序返回分数。"""
    if not req.pairs:
        return {"scores": []}
    query = req.pairs[0][0]
    documents = [pair[1] for pair in req.pairs]
    try:
        scores = await rerank_group(query, documents)
    except Exception as e:
        logger.exception("rerank 调用失败")
        raise HTTPException(status_code=502, detail=f"rerank failed: {e}") from e
    return {"scores": scores}

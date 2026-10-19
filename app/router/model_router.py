import os
from dotenv import load_dotenv
load_dotenv()
import threading

import numpy as np
import torch
from fastapi import APIRouter

from FlagEmbedding import BGEM3FlagModel, FlagReranker
from schema.Request import EmbedRequest, RerankRequest

def _resolve_device(prefer: str = "cuda") -> str:
    if prefer == "cuda" and not torch.cuda.is_available():
        print("[server] CUDA 不可用，自动降级到 CPU")
        return "cpu"
    return prefer

# TODO: 后续优化到ONNX为CPU加速, 如果云部署没有GPU的话
DEVICE = _resolve_device(os.getenv("MODEL_DEVICE", "cuda"))
USE_FP16 = (DEVICE == "cuda")  # CPU 上 fp16 不支持且更慢，强制关闭

embed_model = BGEM3FlagModel(
    os.getenv("BGE_M3_PATH", "/model/bge-m3"),
    device=DEVICE,
    use_fp16=USE_FP16
)

rerank_model = FlagReranker(
    os.getenv("BGE_RERANKER_PATH", "/model/bge-reranker-v2-m3"),
    device=DEVICE,
    use_fp16=USE_FP16
)

_embed_lock = threading.Lock()
_rerank_lock = threading.Lock()

router = APIRouter(prefix="/model")


def _to_jsonable(obj):
    """把 numpy 数组 / 标量 / 嵌套结构递归转成原生 Python 类型，供 JSON 序列化。"""
    if isinstance(obj, np.ndarray):
        return obj.tolist()
    if isinstance(obj, dict):
        return {str(k): _to_jsonable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_to_jsonable(x) for x in obj]
    if isinstance(obj, np.generic):  # numpy 标量：float32 / int64 等
        return obj.item()
    return obj


@router.post("/embeddings")
def embeddings(req: EmbedRequest):
    with _embed_lock:
        output = embed_model.encode(req.texts, return_dense=True, return_sparse=True)
    return {
        "dense_vecs": _to_jsonable(output["dense_vecs"]),
        "lexical_weights": _to_jsonable(output["lexical_weights"]),
    }

@router.post("/rerank")
def rerank(req: RerankRequest):
    with _rerank_lock:
        scores = rerank_model.compute_score(
            req.pairs,
            batch_size=req.batch_size,
            normalize=req.normalize,
        )
    return {"scores": _to_jsonable(scores)}

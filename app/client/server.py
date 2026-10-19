# server.py — 部署在虚拟机上
from fastapi import FastAPI
from FlagEmbedding import BGEM3FlagModel, FlagReranker
from pydantic import BaseModel

app = FastAPI()
embed_model = BGEM3FlagModel("/path/to/bge-m3", device="cuda", use_fp16=True)
rerank_model = FlagReranker("/path/to/bge-reranker-v2-m3", device="cuda", use_fp16=True)

class EmbedRequest(BaseModel):
    texts: list[str]

class RerankRequest(BaseModel):
    query: str
    documents: list[str]
    top_k: int = 5

@app.post("/embeddings")
def embeddings(req: EmbedRequest):
    output = embed_model.encode(req.texts, return_dense=True, return_sparse=True)
    return {
        "dense": output["dense_vecs"].tolist(),
        "sparse": output["lexical_weights"],
    }

@app.post("/rerank")
def rerank(req: RerankRequest):
    scores = rerank_model.compute_score([[req.query, d] for d in req.documents])
    return {"scores": scores}
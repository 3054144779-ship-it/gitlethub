from contextlib import asynccontextmanager

from fastapi import FastAPI

from config.nacos_registry import NacosRegistrar
from router import model_router
from service.models import EMBED_MODEL, RERANK_MODEL


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 不再加载本地模型，import 完成即可对外服务
    registrar = NacosRegistrar(metadata={
        "protocol": "http",
        "embedding_model": EMBED_MODEL,
        "rerank_model": RERANK_MODEL,
    })
    await registrar.start()
    try:
        yield
    finally:
        await registrar.stop()


app = FastAPI(title="gitlet-ai-inference", lifespan=lifespan)
app.include_router(model_router.router)


@app.get("/health")
def health():
    return {"status": "UP", "embedding_model": EMBED_MODEL, "rerank_model": RERANK_MODEL}


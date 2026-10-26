from contextlib import asynccontextmanager

from fastapi import FastAPI

from config.nacos_registry import NacosRegistrar
from router import model_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 模型在 import model_router 时已加载完，此刻注册，实例一上线即可服务
    registrar = NacosRegistrar(metadata={
        "protocol": "http",
        "model": "bge-m3",
        "device": model_router.DEVICE,
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
    return {"status": "UP", "device": model_router.DEVICE}


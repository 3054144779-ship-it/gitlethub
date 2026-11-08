import asyncio
import logging
from contextlib import asynccontextmanager

import yaml
from fastapi import FastAPI

from config.Container import Container
from config.nacos_registry import NacosRegistrar, SERVICE_NAME
from router.chat_router import router as chat_router
from worker.warmup_models import warmup_models

import os

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


@asynccontextmanager
async def lifespan(app: FastAPI):
    registrar = NacosRegistrar(metadata={"protocol": "http"})
    await registrar.start()

    container = None
    try:
        container = Container()
        with open(os.path.join(PROJECT_ROOT, "config.yaml"), encoding="utf-8") as f:
            config_data = yaml.safe_load(f)
        # 容器内模板路径与开发机不同，用环境变量覆盖 config.yaml 里的 template_dir
        template_dir = os.getenv("JINJA_TEMPLATE_DIR")
        if template_dir:
            config_data["config"]["jinja"]["template_dir"] = template_dir
        container.config.from_dict(config_data["config"])
        # 索引链路已拆到 worker/main.py，这里不再需要 worker.index_worker
        container.wire(modules=[
            "router.chat_router",
            "core.intent_clarifier",
            "schema.nodes.node",
            "service.impl.ai_service_impl",
            "service.impl.explain_service_impl",
            "service.impl.archaeology_service_impl",
            "service.impl.semantic_search_service_impl",
            "service.impl.review_serivce_impl",
        ])

        # 预热模型
        await warmup_models(container)

        # 把容器挂到 app.state，供路由层访问
        app.state.container = container
        yield
    finally:
        if container is not None:
            for name, provider in (
                ("EmbeddingModel", container.embedding_model),
                ("Reranker", container.reranker),
            ):
                try:
                    await provider().close_client()
                except Exception as e:
                    logging.warning("关闭 %s 客户端失败: %s", name, e)
            try:
                await container.redis_client().aclose()
            except Exception as e:
                logging.warning("关闭 Redis 客户端失败: %s", e)
            try:
                container.milvus_client().close()
            except Exception as e:
                logging.warning("关闭 Milvus 客户端失败: %s", e)
        await registrar.stop()

app = FastAPI(lifespan=lifespan)
app.include_router(chat_router)


@app.get("/health")
def health():
    return {"status": "UP", "service": SERVICE_NAME}


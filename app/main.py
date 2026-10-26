import asyncio
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

    mq_task = None
    try:
        container = Container()
        with open(os.path.join(PROJECT_ROOT, "config.yaml"), encoding="utf-8") as f:
            config_data = yaml.safe_load(f)
        container.config.from_dict(config_data["config"])
        container.wire(modules=[
            "router.chat_router",
            "core.intent_clarifier",
            "schema.nodes.node",
            "worker.index_worker",
            "service.impl.ai_service_impl",
            "service.impl.explain_service_impl",
            "service.impl.archaeology_service_impl",
            "service.impl.semantic_search_service_impl",
            "service.impl.review_serivce_impl",
        ])

        # 预热模型
        await asyncio.to_thread(warmup_models, container)

        # 把容器挂到 app.state，供路由层访问
        app.state.container = container
        # 启动 MQ 消费者, 后台任务
        from worker.index_worker import code_parser_mq_consumer
        mq_task = asyncio.create_task(code_parser_mq_consumer())
        yield
    finally:
        if mq_task is not None:
            mq_task.cancel()
        await registrar.stop()

app = FastAPI(lifespan=lifespan)
app.include_router(chat_router)


@app.get("/health")
def health():
    return {"status": "UP", "service": SERVICE_NAME}


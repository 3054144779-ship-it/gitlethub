import asyncio
import logging
import os

import yaml
from langchain_core.documents import Document

from config.Container import Container
from worker.index_worker import code_parser_mq_consumer

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


async def main():
    # 否则 warmup 里的 logger.info/error 在 worker 进程里完全看不到
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )
    container = Container()
    with open(os.path.join(PROJECT_ROOT, "config.yaml"), encoding="utf-8") as f:
        config_data = yaml.safe_load(f)
    template_dir = os.getenv("JINJA_TEMPLATE_DIR")
    if template_dir:
        config_data["config"]["jinja"]["template_dir"] = template_dir
    container.config.from_dict(config_data["config"])
    container.wire(modules=["worker.index_worker"])

    try:
        # to_vector 已是 async，直接 await；塞进 to_thread 会得到一个永不执行的 coroutine
        await container.embedding_model().to_vector(
            [Document(page_content="模型预热 warmup: public void blacklist() {}")]
        )
        # 启动 MQ 消费者, 后台任务
        await code_parser_mq_consumer()
    finally:
        try:
            await container.embedding_model().close_client()
        except Exception as e:
            logging.warning("关闭 EmbeddingModel 客户端失败: %s", e)

        try:
            await container.redis_client().aclose()
        except Exception as e:
            logging.warning("关闭 Redis 客户端失败: %s", e)

        try:
            container.milvus_client().close()
        except Exception as e:
            logging.warning("关闭 Milvus 客户端失败: %s", e)


if __name__ == "__main__":
    asyncio.run(main())
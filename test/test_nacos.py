import asyncio
import logging

from app.config.nacos_registry import NacosRegistrar

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


async def main():
    r = NacosRegistrar(metadata={"protocol": "http", "model": "bge-m3"})
    print(f"准备注册 {r.ip}:{r.port}")
    await r.start()
    print("看控制台：http://192.168.12.168:8848/nacos → 服务管理 → 服务列表")
    try:
        await asyncio.sleep(300)
    except KeyboardInterrupt:
        pass
    finally:
        await r.stop()


if __name__ == "__main__":
    asyncio.run(main())

import asyncio
import logging
import os
import socket
from pathlib import Path

from dotenv import load_dotenv
from v2.nacos import (
    ClientConfigBuilder,
    DeregisterInstanceParam,
    GRPCConfig,
    NacosNamingService,
    RegisterInstanceParam,
)
from v2.nacos.transport.http_agent import HttpAgent

# 固定指向项目根目录的 .env，避免受启动时 cwd 影响
load_dotenv(Path(__file__).resolve().parents[2] / ".env")

logger = logging.getLogger("nacos")

NACOS_SERVER = os.getenv("NACOS_SERVER", "127.0.0.1:8848")
NACOS_NAMESPACE = os.getenv("NACOS_NAMESPACE", "")
NACOS_USERNAME = os.getenv("NACOS_USERNAME", "")
NACOS_PASSWORD = os.getenv("NACOS_PASSWORD", "")
NACOS_GROUP = os.getenv("NACOS_GROUP", "DEFAULT_GROUP")
SERVICE_NAME = os.getenv("SERVICE_NAME", "gitlet-ai-helper")
SERVICE_PORT = int(os.getenv("SERVICE_PORT", "8000"))
SERVICE_IP = os.getenv("SERVICE_IP", "")
SERVICE_CLUSTER = os.getenv("SERVICE_CLUSTER", "DEFAULT")

# ---------------------------------------------------------------------------
# 兼容 Nacos 2.x 服务端：
# nacos-sdk-python 3.x 的登录接口是 3.x 才有的 {ctx}/v1/auth/users/login，
# 而 Nacos 2.x 只有 {ctx}/v1/auth/login。
# ---------------------------------------------------------------------------
_orig_http_request = HttpAgent.request


async def _patched_http_request(self, url, method, headers=None, params=None, data=None):
    if "/v1/auth/users/login" in url:
        url = url.replace("/v1/auth/users/login", "/v1/auth/login")
        logger.info("[nacos-patch] 登录接口适配 Nacos 2.x -> %s", url)
    return await _orig_http_request(self, url, method, headers, params, data)


HttpAgent.request = _patched_http_request


def _local_ip() -> str:
    """没配 SERVICE_IP 时自动探测默认出口网卡地址。"""
    if SERVICE_IP:
        return SERVICE_IP
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


class NacosRegistrar:
    """以临时实例身份注册到 Nacos（SDK 3.x gRPC + Nacos 2.x 鉴权补丁）。"""

    def __init__(self, metadata: dict | None = None):
        self.ip = _local_ip()
        self.port = SERVICE_PORT
        self.metadata = {str(k): str(v) for k, v in (metadata or {}).items()}
        self.client: NacosNamingService | None = None

    def _client_config(self):
        builder = (
            ClientConfigBuilder()
            .server_address(NACOS_SERVER)
            .namespace_id(NACOS_NAMESPACE)
            .log_level("INFO")
            .grpc_config(GRPCConfig(grpc_timeout=10000))
        )
        if NACOS_USERNAME:
            builder = builder.username(NACOS_USERNAME).password(NACOS_PASSWORD)
        return builder.build()

    async def start(self, retries: int = 3):
        logger.info(
            "准备注册: server=%s namespace=%r group=%s instance=%s:%s",
            NACOS_SERVER, NACOS_NAMESPACE, NACOS_GROUP, self.ip, self.port,
        )
        for attempt in range(1, retries + 1):
            try:
                self.client = await NacosNamingService.create_naming_service(self._client_config())
                ok = await self.client.register_instance(
                    request=RegisterInstanceParam(
                        service_name=SERVICE_NAME,
                        group_name=NACOS_GROUP,
                        ip=self.ip,
                        port=self.port,
                        weight=1.0,
                        cluster_name=SERVICE_CLUSTER,
                        metadata=self.metadata,
                        enabled=True,
                        healthy=True,
                        ephemeral=True,
                    )
                )
                logger.info("已注册到 Nacos: %s -> %s:%s (%s)", SERVICE_NAME, self.ip, self.port, ok)
                return
            except Exception as e:
                logger.error("注册 Nacos 失败(第 %s/%s 次): %s: %s",
                             attempt, retries, type(e).__name__, e)
                self.client = None
                if attempt < retries:
                    await asyncio.sleep(3)
        logger.error("注册 Nacos 最终失败，服务继续以直连方式提供能力")

    async def stop(self):
        if not self.client:
            return
        try:
            await self.client.deregister_instance(
                request=DeregisterInstanceParam(
                    service_name=SERVICE_NAME,
                    group_name=NACOS_GROUP,
                    ip=self.ip,
                    port=self.port,
                    cluster_name=SERVICE_CLUSTER,
                    ephemeral=True,
                )
            )
            logger.info("已从 Nacos 摘除: %s", SERVICE_NAME)
        except Exception:
            logger.exception("摘除 Nacos 实例失败")
        finally:
            try:
                await self.client.shutdown()
            except Exception:
                logger.warning("关闭 Nacos 客户端连接失败")

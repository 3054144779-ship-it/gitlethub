from typing import List

from dependency_injector.wiring import Provide
from redis import Redis

from config.Container import Container


class ProcedureStore:
    """
    程序性记忆存储：按 global/repo/user 三层作用域存取“某类任务怎么做”的 SOP。
    读取时最具体的层优先（user > repo > global）。
    """
    SCOPE_GLOBAL = "global"
    SCOPE_REPO = "repo"
    SCOPE_USER = "user"

    def __init__(self, redis_client: Redis):
        self.redis_client = redis_client

    def _key(
            self,
            scope: str,
            scope_id: int | None,
            intent: str
    ) -> str:
        """
        构造 Redis 键：procedure:{scope}:{scope_id}:{intent}
        """
        return f"procedure:{scope}:{scope_id or ''}:{intent}"

    async def get(
            self,
            intent: str,
            user_id: int | None = None,
            repo_id: int | None = None,
            merge: bool = True,
            separator: str = "\n",
    ) -> str:
        """
        取某意图的 SOP，按 user > repo > global 逐层查找。
        :param separator: 分割符
        :param repo_id: 仓库 ID
        :param user_id: 用户 ID
        :param intent: 意图
        :param merge: True=叠加(通用打底+具体补充)；False=替换(命中最具体即返回)
        :return: 拼好的 SOP 文本；没有任何层则返回空串
        """
        # 从最具体到最通用
        layers = [
            (self.SCOPE_USER, user_id),
            (self.SCOPE_REPO, repo_id),
            (self.SCOPE_GLOBAL, None),
        ]
        found: list[str] = []
        for scope, scope_id in layers:
            raw = await self.redis_client.get(self._key(scope, scope_id, intent))
            value = self._decode(raw)
            if value:
                found.append(value)
                if not merge:
                    return value  # 替换式：最具体层命中即返回
        # 叠加式：反转让通用在前、具体在后（具体作为强调放末尾）
        found.reverse()
        return separator.join(found)

    async def put(
            self,
            intent: str,
            procedure: str,
            scope: str = SCOPE_GLOBAL,
            scope_id: int | None = None,
            ttl: int | None = None,
    ) -> None:
        """
        写入/更新某一层某意图的 SOP；ttl=None 表示常驻
        """
        key = self._key(scope, scope_id, intent)
        if ttl:
            await self.redis_client.set(key, procedure, ex=ttl)
        else:
            await self.redis_client.set(key, procedure)

    async def delete(self, intent: str, scope: str = SCOPE_GLOBAL, scope_id: int | None = None) -> None:
        """
        删除某一层某意图的 SOP
        """
        await self.redis_client.delete(self._key(scope, scope_id, intent))

    @staticmethod
    def _decode(value) -> str:
        """未开 decode_responses，get 返回 bytes，需解码为 str"""
        if value is None:
            return ""
        return value.decode() if isinstance(value, bytes) else value

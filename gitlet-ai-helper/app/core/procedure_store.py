from typing import List

from redis.asyncio import Redis
from redis.exceptions import WatchError


class ProcedureStore:
    """
    程序性记忆存储：以 Redis List 存储（key -> list，每条规则一个元素）。
    按 global/repo/user 三层作用域存取，读取时最具体的层优先（user > repo > global）。
    所有多命令操作均走 pipeline；追加去重使用 WATCH + MULTI 乐观锁保证原子。
    """
    SCOPE_GLOBAL = "global"
    SCOPE_REPO = "repo"
    SCOPE_USER = "user"

    # 乐观锁重试次数
    MAX_RETRY = 3

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

    def _decode_list(self, items) -> List[str]:
        """批量解码 LRANGE 结果（未开 decode_responses，返回 bytes）"""
        return [self._decode(item) for item in items if item is not None]

    async def get(
            self,
            intent: str,
            user_id: int | None = None,
            repo_id: int | None = None,
            merge: bool = True,
            separator: str = "\n",
    ) -> str:
        """
        取某意图的 SOP，按 user > repo > global 逐层查找（三层 LRANGE 合并为一次往返）。
        :param merge: True=叠加(通用打底+具体在后)；False=替换(命中最具体即返回)
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
        # 三次 LRANGE 打包进一个 pipeline(非事务)，省往返
        async with self.redis_client.pipeline(transaction=False) as pipe:
            for scope, scope_id in layers:
                pipe.lrange(self._key(scope, scope_id, intent), 0, -1)
            results = await pipe.execute()

        found: List[str] = []
        for items in results:
            rules = self._decode_list(items)
            if not rules:
                continue
            if not merge:
                return separator.join(rules)  # 替换式：最具体层命中即返回
            found.append(separator.join(rules))

        # 反转让通用在前、具体在后（具体作为强调放末尾）
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
        """整条覆盖：DELETE + RPUSH + EXPIRE 放进同一事务，消除中间空窗"""
        key = self._key(scope, scope_id, intent)
        rules = [line for line in procedure.split("\n") if line.strip()]
        async with self.redis_client.pipeline(transaction=True) as pipe:
            pipe.delete(key)
            if rules:
                pipe.rpush(key, *rules)
                if ttl:
                    pipe.expire(key, ttl)
            await pipe.execute()

    async def append(
            self,
            intent: str,
            procedure: str,
            scope: str = SCOPE_GLOBAL,
            scope_id: int | None = None,
            ttl: int | None = None,
    ) -> None:
        """
        追加一条规则到某层某意图（同层去重，不覆盖已有规则）。
        使用 WATCH + MULTI 乐观锁：读-判断-写之间若被改动则重试。
        :param intent: 意图
        :param procedure: 规则文本
        :param scope: 作用域
        :param scope_id: 作用域 ID
        :param ttl: 过期时间
        :return: None
        """
        key = self._key(scope, scope_id, intent)
        for _ in range(self.MAX_RETRY):
            try:
                async with self.redis_client.pipeline(transaction=True) as pipe:
                    await pipe.watch(key)
                    # WATCH 之后为即时执行模式，直接拿到当前值
                    existing = self._decode_list(await pipe.lrange(key, 0, -1))
                    if procedure in existing:
                        await pipe.unwatch()
                        return
                    # 进入事务，后续命令排队，EXEC 时若 key 被改则抛 WatchError
                    pipe.multi()
                    pipe.rpush(key, procedure)
                    if ttl:
                        pipe.expire(key, ttl)
                    await pipe.execute()
                    return
            except WatchError:
                # 期间被其他写入抢先，重读重试
                continue

    async def delete(self, intent: str, scope: str = SCOPE_GLOBAL, scope_id: int | None = None) -> None:
        """
        删除某一层某意图的 SOP
        """
        await self.redis_client.delete(self._key(scope, scope_id, intent))

    @staticmethod
    def _decode(value) -> str:
        """未开 decode_responses，LRANGE 返回 bytes，需解码为 str"""
        if value is None:
            return ""
        return value.decode() if isinstance(value, bytes) else value

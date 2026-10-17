"""
    AI Service 实现

"""
import asyncio
import json
from typing import List

from dependency_injector.wiring import Provide, inject
from langgraph.graph.state import CompiledStateGraph

from config.Container import Container
from schema.domain import ChatDTO
from schema.response import Response
from service.ai_service import ChatService

class ChatServiceImpl(ChatService):

    def __init__(self):
        pass

    @inject
    async def chat(
            self,
            chat_dto: ChatDTO,
            graph: CompiledStateGraph = Provide[Container.flow_chart]
    ) -> Response:

        res = await graph.ainvoke(chat_dto)

        return Response.ok(res)

if __name__ == '__main__':
    import yaml
    from time import time
    container = Container()
    with open(r"D:\Job_project\gitlet_ai_helper\config.yaml", encoding="utf-8") as f:
        config_data = yaml.safe_load(f)
    container.config.from_dict(config_data["config"])
    container.wire(modules=[
        __name__,
        "core.intent_clarifier",
        "schema.nodes.node",
    ])

    prompt = """
    执行操作：代码解释（开发者备注：有且只有这一个意图）
    public void blacklist(String authorization) {
    String pureToken = stripBearer(authorization);
    if (StrUtil.isEmpty(pureToken)) {
        return;
    }
    try {
        JWT jwt = JWTUtil.parseToken(pureToken);
        long expMillis = toEpochMillis(jwt.getPayload().getClaim("exp"));
        long remaining = expMillis - System.currentTimeMillis();
        if (remaining > 0) {
            stringRedisTemplate.opsForValue().set(
                    blacklistKey(pureToken), "logout", remaining, TimeUnit.MILLISECONDS);
        }
    } catch (Exception e) {
        log.warn("登出解析 token 失败", e);
    }
}
    这段代码是什么意思，有啥问题吗？改了会影响啥？
    """

    chat = ChatServiceImpl()
    print("开始执行")
    start_time = time()
    chat_chat = asyncio.run(chat.chat(ChatDTO(query=prompt, owner=10001, repo=20005, context=None)))
    print(chat_chat)
    print(f"耗时: {time() - start_time}秒")
    print("执行结束")


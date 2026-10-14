"""
    AI Service 实现

"""
import json
from typing import List

from schema.domain import IntentResult
from schema.response import Response
from service.ai_service import ChatService
from core.intent_clarifier import IntentClarifier



class ChatServiceImpl(ChatService):

    async def chat(self, query: str, owner: int, repo: int) -> Response:
        # 创建意图识别器
        intent = IntentClarifier()

        # 意图识别
        async def get_intent(count: int, user_query: str):
            intent_result = await intent.clarify_intent(user_query)
            if await format_validator(intent_result) or count >= 3:
                # base case
                print("意图识别结果: ", intent_result)
                return intent_result
            return await get_intent(count + 1, user_query)

        async def format_validator(intent_result: IntentResult) -> bool:
            """
            格式化验证
            :param intent_result: 意图
            :return: 验证结果
            """
            if (intent_result is not None
                    and intent_result.need_clarify is not None
                    and intent_result.intent is not None):
                # 如果意图结果不为空且不需要 clarification 且意图长度大于0 则校验成功
                if len(intent_result.intent) > 0 or intent_result.need_clarify is False:
                    return True
                else:
                    return False
            return False

        # 获取意图
        user_intent = await get_intent(0, query)

        if await format_validator(user_intent):
            # 如果意图识别结果为空且已达到最大重试次数
            return Response.ok(data=user_intent)

        # 根据意图执行逻辑
        async def obtain_each_intention(current_intention_list: List[str]):
            for intention in current_intention_list:
                yield intention

        async def distribution(current_intention):
            if current_intention == "explain":
                # 执行代码解释逻辑
                pass
            elif current_intention == "review":
                # 执行代码审查逻辑
                pass
            elif current_intention == "archaeology":
                # 执行代码考古逻辑
                return Response.ok(data={"message": "正在执行意图逻辑"})
            elif current_intention == "search":
                # 执行代码搜索逻辑
                pass


        async for current_intention in obtain_each_intention(user_intent.intent):
            print("意图识别结果: ", current_intention)
            await distribution(current_intention)


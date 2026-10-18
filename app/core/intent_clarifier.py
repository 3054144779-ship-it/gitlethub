"""
意图识别器，用于识别用户意图
"""
from typing import Dict, Union, List

from dependency_injector.wiring import inject, Provide
from jinja2 import Template
from langchain_core.language_models import BaseChatModel
from langchain_core.output_parsers import JsonOutputParser
from langchain_core.runnables import Runnable, RunnableLambda
from openai import RateLimitError

from client.llm_client import LLMClient
from schema.domain import IntentResult
from config.Container import Container

class IntentClarifier:
    """
    意图识别器
    """

    @inject
    def __init__(self,
                 llm_client: BaseChatModel=Provide[Container.intent_clarifier_llm_client],
                 intent_clarifier_template: Template=Provide[Container.intent_clarifier_template]):
        # 用于意图识别的小模型
        self.llm_client = llm_client.bind(extra_body={"enable_thinking": False}).with_retry(
            retry_if_exception_type=(RateLimitError,),
            wait_exponential_jitter=True,
            stop_after_attempt=5,
        )
        # 意图识别模板
        self.intent_clarifier_template = intent_clarifier_template
        # json解析器
        self.json_parser = JsonOutputParser(pydantic_object=IntentResult)

        # 意图识别链
        self.clarify_intent_chain = (
                RunnableLambda(self._render_prompt)  # type: ignore
                | self.llm_client
                | self.json_parser
        )

    def _render_prompt(self, data: Dict[str, str]) -> str:
        return self.intent_clarifier_template.render(query=data["query"])

    async def clarify_intent(self, query) -> IntentResult:
        """
        识别用户意图
        :param query: 用户输入
        :return: 意图
        示例：
        {
            "intent": ["explain"],
            "confidence": 0.8,
            "reason": "用户正在请求对某个概念的解释。",
            "need_clarify": False
        }

        """
        result = await self.clarify_intent_chain.ainvoke({"query": query})
        return IntentResult.model_validate(result)

async def intent_recognition(query) -> IntentResult | None:
    """
    意图识别
    :param query: 用户输入
    :return: 意图
    """

    # 创建意图识别器
    clarifier = IntentClarifier()

    # 意图识别
    async def get_intent(count: int, user_query: str) -> IntentResult | None:
        intent_result = await clarifier.clarify_intent(user_query)
        if await format_validator(intent_result):
            print("意图识别结果: ", intent_result)
            return intent_result
        if count >= 2:
            print("意图识别重试次数已达上限，返回最后一次结果: ", intent_result)
            return intent_result
        return await get_intent(count + 1, user_query)

    async def format_validator(intent_result: IntentResult) -> bool:
        """
        格式化验证
        :param intent_result: 意图
        :return: 验证结果
        """
        if intent_result is None:
            return False
        if intent_result.need_clarify is None or intent_result.intent is None:
            return False
        return True

    # 获取意图
    user_intent = await get_intent(0, query)

    if not await format_validator(user_intent):
        # 如果意图识别结果无效，则返回一个默认的意图识别结果，提示用户重新描述
        return IntentResult(intent=[], confidence=0.0, reason="未能识别您的意图，请再描述一下？", need_clarify=True)

    return user_intent

if __name__ == '__main__':
    import asyncio
    import yaml
    container = Container()
    with open(r"D:\Job_project\gitlet_ai_helper\config.yaml", encoding="utf-8") as f:
        config_data = yaml.safe_load(f)
    container.config.from_dict(config_data["config"])
    container.wire(modules=[__name__])

    async def main():
        clarifier = IntentClarifier()
        result = await clarifier.clarify_intent("历史代码查询")
        print(result)

    asyncio.run(main())
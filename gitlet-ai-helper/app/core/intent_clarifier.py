"""
意图识别器，用于识别用户意图
"""
from typing import Any, Dict, Union, List

from dependency_injector.wiring import inject, Provide
from jinja2 import Template
from langchain_core.language_models import BaseChatModel
from langchain_core.output_parsers import JsonOutputParser
from langchain_core.runnables import Runnable, RunnableLambda
from openai import RateLimitError

from client.llm_client import LLMClient
from schema.domain import IntentResult
from config.Container import Container

# 合法的意图枚举，用于过滤模型幻觉值
VALID_INTENTS = ("explain", "review", "archaeology", "search")

class IntentClarifier:
    """
    意图识别器
    """

    @inject
    def __init__(self,
                 llm_client: BaseChatModel=Provide[Container.intent_clarifier_llm_client],
                 intent_clarifier_template: Template=Provide[Container.intent_clarifier_template]):
        # 用于意图识别的小模型
        self.llm_client = llm_client.bind(extra_body={"enable_thinking": False})
        # 意图识别模板
        self.intent_clarifier_template = intent_clarifier_template
        # json解析器：langchain-core>=1.x 的 JsonOutputParser 已不再按 pydantic_object 校验，
        # 这里只做 JSON 提取，结构校验交给 _normalize
        self.json_parser = JsonOutputParser()

        # 意图识别链
        self.clarify_intent_chain = (
                RunnableLambda(self._render_prompt)  # type: ignore
                | self.llm_client
                | self.json_parser
        )

    def _render_prompt(self, data: Dict[str, str]) -> str:
        return self.intent_clarifier_template.render(query=data["query"])

    @staticmethod
    def _normalize(raw: Any) -> Dict[str, Any]:
        """
        将模型输出的各种非标准结构归一化为 IntentResult 可接受的 dict
        :param raw: 模型解析后的原始结果
        :return: 归一化后的 dict
        """
        if isinstance(raw, IntentResult):
            return raw.model_dump()

        # 模型直接返回 ["archaeology", "search"] 的兜底
        if isinstance(raw, list):
            raw = {"intent": raw}

        # 模型返回 {"intents": [...]} / {"intent": [...]} 的兜底
        if isinstance(raw, dict) and "intent" not in raw and "intents" in raw:
            raw = {**raw, "intent": raw["intents"]}

        if not isinstance(raw, dict):
            raise ValueError(f"意图识别结果结构非法: {raw!r}")

        intent_value = raw.get("intent")
        if isinstance(intent_value, str):
            intent_value = [intent_value]
        if not isinstance(intent_value, list):
            intent_value = []

        # 过滤非法枚举并去重，保持模型给出的顺序
        intents = [
            item for item in dict.fromkeys(intent_value)
            if isinstance(item, str) and item in VALID_INTENTS
        ]

        try:
            confidence = float(raw.get("confidence") or 0.0)
        except (TypeError, ValueError):
            confidence = 0.0
        confidence = min(max(confidence, 0.0), 1.0)

        return {
            "intent": intents,
            "confidence": confidence,
            "reason": str(raw.get("reason") or ""),
            # 意图为空时必然需要澄清，避免空意图 + need_clarify=False 落到澄清节点却没有话术
            "need_clarify": bool(raw.get("need_clarify")) or not intents,
        }

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
        return IntentResult.model_validate(self._normalize(result))

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
        try:
            intent_result = await clarifier.clarify_intent(user_query)
        except Exception as e:
            # 解析失败 / 结构非法 / 校验失败都不应击穿图，重试即可
            print(f"意图识别第 {count + 1} 次失败: {e!r}")
            if count >= 2:
                return None
            return await get_intent(count + 1, user_query)

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
        return IntentResult(intent=[], confidence=0.0, reason="我没能准确理解你的意图，能具体说说你想了解哪部分代码吗？", need_clarify=True)

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
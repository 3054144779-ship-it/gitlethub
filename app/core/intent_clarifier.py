"""
意图识别器，用于识别用户意图
"""
from typing import Dict

from dependency_injector.wiring import inject, Provide
from jinja2 import Template
from langchain_core.language_models import BaseChatModel
from langchain_core.output_parsers import JsonOutputParser, format_instructions
from langchain_core.runnables import Runnable, RunnableLambda

from client.llm_client import LLMClient
from schema.domain import IntentResult

from config.Container import Container


class IntentClarifier:
    """
    意图识别器，用于识别用户意图
    """

    @inject
    def __init__(self,
                 llm_client: BaseChatModel,
                 intent_clarifier_template: Template=Provide[Container.intent_clarifier_template]):
        # 用于意图识别的小模型
        self.llm_client = llm_client
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
            "intent": "explain",
            "confidence": 0.8,
            "reason": "用户正在请求对某个概念的解释。",
            "need_clarify": False
        }

        """
        return await self.clarify_intent_chain.ainvoke({"query": query})

if __name__ == '__main__':
    import asyncio
    import yaml
    container = Container()
    with open(r"D:\Job_project\gitlet_ai_helper\config.yaml", encoding="utf-8") as f:
        config_data = yaml.safe_load(f)
    container.config.from_dict(config_data["config"])
    container.wire(modules=[__name__])

    llm_client = LLMClient(
        model="deepseek-chat",
        model_provider="deepseek",
        config={
            "api_key": "***REMOVED***",
            "base_url": "https://api.deepseek.com"
        }
    ).llm

    async def main():
        if llm_client is not None:
            clarifier = IntentClarifier(llm_client=llm_client)
            result = await clarifier.clarify_intent("你能干什么？")
            print(result)
        else:
            print("LLM client is not initialized")

    asyncio.run(main())
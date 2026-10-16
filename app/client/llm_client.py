from typing import List, Any

from langchain.chat_models import init_chat_model
from langchain.agents import create_agent
from langchain_core.language_models import BaseChatModel
from langgraph.types import Checkpointer

class LLMClient:
    """
    LLM 客户端
    """
    def __init__(
            self,
            model: str | None = None,
            model_provider: str | None = None,
            llm: BaseChatModel | None = None,
            tools: List[Any] | None = None,
            config: dict | None = None
    ):
        """
        两种构建方式，一种是通过 model 和 model_provider 参数构建 llm，另一种是通过 llm 参数直接传入 llm 模型
        :param model: 模型名称
        :param model_provider: 模型提供者
        :param llm: 模型
        :param tools: 工具列表
        :param config: 配置
        """
        if llm is not None:
            self.llm = llm
        elif model is not None:
            self.llm = init_chat_model(
                model=model,
                model_provider=model_provider,
                **(config or {})
            )
        else:
            raise ValueError("必须提供 llm 参数或 model 参数")

        self.tools = tools

    def create_agent(
            self,
            system_prompt: str | None = None,
            checkpointer: Checkpointer | None = None,
            middleware: List[Any] | None = None,
            config: dict | None = None
    ):
        """
        创建 Agent
        :param system_prompt: 系统提示词
        :param checkpointer: 检查点器（用于持久化对话状态）
        :param middleware: 中间件列表（由调用方自行构建）
        :param config: 其他 create_agent 的额外配置
        """
        return create_agent(
            model=self.llm,
            tools=self.tools,
            system_prompt=system_prompt,
            # TODO: 后续添加 checkpointer 和 middleware 参数
            # checkpointer=checkpointer,
            # middleware=middleware,
            **(config or {})
        )

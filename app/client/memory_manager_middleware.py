from typing import Any, NotRequired, List

from langchain.agents import AgentState
from langchain.agents.middleware import AgentMiddleware
from langchain.agents.middleware.types import StateT
from langchain_core.messages import AnyMessage
from langgraph.runtime import Runtime
from langgraph.typing import ContextT

from core.memory_manager import MemoryManager

class MemoryAgentState(AgentState):
    """内层 agent 的扩展状态：在默认 messages 之外补充记忆所需字段"""
    session_id: str
    query: str

class MemoryManagerMiddleware(AgentMiddleware):
    """
    记忆分层中间件
    """
    state_schema = MemoryAgentState

    def __init__(
            self,
            memory_manager : MemoryManager,
            *args,
            **kwargs
    ):
        super().__init__(*args, **kwargs)
        self.memory_manager = memory_manager

    async def abefore_agent(self, state: StateT, runtime: Runtime[ContextT]) -> dict[str, Any] | None:
        """
        在agent执行前，从内存管理器中获取历史会话，注入到节点状态中
        :param state: 节点状态
        :param runtime: 运行时
        :return: 节点状态
        """
        history_messages = await self.memory_manager.get_history_messages(state["session_id"], state["query"])
        return {
            "history_messages": history_messages,
        }

    async def aafter_agent(self, state: StateT, runtime: Runtime[ContextT]) -> dict[str, Any] | None:
        # 每次执行后，进行一次简单的语义压缩
        await self.memory_manager.memory_compress(state["session_id"], state["messages"])
        # 添加消息到内存管理器中
        await self.memory_manager.add_message(state["session_id"], state["messages"])

        return None


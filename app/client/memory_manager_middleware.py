from typing import Any

from langchain.agents.middleware import AgentMiddleware
from langchain.agents.middleware.types import StateT
from langgraph.runtime import Runtime
from langgraph.typing import ContextT

from core.memory_manager import MemoryManager

class MemoryManagerMiddleware(AgentMiddleware):
    """
    记忆分层中间件
    """
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
        history_messages = self.memory_manager.get_history_messages(state["session_id"], state["query"])
        return {
            "history_messages": history_messages,
        }

    async def aafter_agent(self, state: StateT, runtime: Runtime[ContextT]) -> dict[str, Any] | None:
        # 添加消息到内存管理器中
        await self.memory_manager.add_message(state["session_id"], state["llm_message"])
        return None


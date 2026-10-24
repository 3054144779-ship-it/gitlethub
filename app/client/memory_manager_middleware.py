import asyncio
import logging
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

    async def aafter_agent(self, state: StateT, runtime: Runtime[ContextT]) -> dict[str, Any] | None:
        session_id = state["session_id"]
        messages = state["messages"]

        # 1) 先把本轮消息落盘（内部按 token 阈值触发压缩）
        try:
            await self.memory_manager.add_message(session_id, messages)
        except Exception:
            logging.exception("add_message 失败 session_id=%s", session_id)

        # 2) 再做本轮主动语义沉淀（抽稳定事实入 Milvus）
        try:
            await self.memory_manager.memory_compress(session_id, messages)
        except Exception:
            logging.exception("memory_compress 失败 session_id=%s", session_id)

        return None


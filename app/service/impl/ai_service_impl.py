"""
    AI Service 实现

"""
import json
from typing import List

from dependency_injector.wiring import Provide, inject
from langgraph.graph.state import CompiledStateGraph

from config.Container import Container
from schema.response import Response
from service.ai_service import ChatService

class ChatServiceImpl(ChatService):

    @inject
    async def chat(
            self,
            query: str,
            owner: int,
            repo: int,
            graph: CompiledStateGraph = Provide[Container.flow_chart]
    ) -> Response:

        res = graph.invoke({
            "query": query,
            "owner": owner,
            "repo": repo
        })

        return Response.ok(res)

"""
    AI Service

"""

from abc import ABC, abstractmethod
from typing import AsyncGenerator, Any


class ChatService(ABC):
    @abstractmethod
    async def chat(self, query: str, owner: int, repo: int) -> AsyncGenerator[Any | None, None]:
        pass
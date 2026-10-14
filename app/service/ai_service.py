"""
    AI Service

"""

from abc import ABC, abstractmethod


class ChatService(ABC):
    @abstractmethod
    async def chat(self, query: str, owner: int, repo: int) -> str:
        pass
from abc import ABC, abstractmethod


class ArchaeologyService(ABC):
    @abstractmethod
    async def archaeology(self, query: str, owner: int, repo: int):
        pass
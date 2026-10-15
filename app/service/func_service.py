from abc import ABC, abstractmethod
from typing import List


class ArchaeologyService(ABC):
    @abstractmethod
    async def retrieval(self, query: str, owner: int, repo: int):
        pass

    @abstractmethod
    async def rerank(self, query: str, code_docs: List[dict], commit_docs: List[dict]):
        pass

    @abstractmethod
    async def prompt(self, query: str, retrieval_rerank_result: dict, owner: int, repo: int):
        pass

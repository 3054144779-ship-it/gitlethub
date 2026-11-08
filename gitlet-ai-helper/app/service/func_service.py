from abc import ABC, abstractmethod
from typing import List

from schema.domain import Context


class ArchaeologyService(ABC):
    @abstractmethod
    async def retrieval(self, query: str, owner: int, repo: int, context: Context | None = None):
        pass

    @abstractmethod
    async def rerank(self, query: str, code_docs: List[dict], commit_docs: List[dict]):
        pass

    @abstractmethod
    async def prompt(self, query: str, retrieval_rerank_result: dict, owner: int, repo: int):
        pass

class ExplainService(ABC):
    @abstractmethod
    async def retrieval(self, query: str, owner: int, repo: int, context: Context | None = None):
        pass

    @abstractmethod
    async def rerank(self, query: str, code_docs: List[dict], commit_docs: List[dict]):
        pass

    @abstractmethod
    async def prompt(self, query: str, retrieval_rerank_result: dict, owner: int, repo: int, review_answer: str):
        pass

class ReviewService(ABC):
    @abstractmethod
    async def retrieval(self, query: str, owner: int, repo: int, context: Context | None):
        pass

    @abstractmethod
    async def rerank(self, query: str, docs_name: str, docs: List[dict]):
        pass

    @abstractmethod
    async def prompt(
            self,
            query: str,
            owner: int,
            repo: int,
            retrieval_rerank_result: dict,
            model: int = 2,
    ):
        pass

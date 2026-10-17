from dataclasses import dataclass
from typing import List, Dict, Literal, TypedDict

from pydantic import BaseModel, Field
from pymilvus import DataType

from schema.base_model import Metadata, CommitMessage


@dataclass
class MilvusPO:
    id: int | None
    user_id: int
    repository_id: int
    file_path: str
    commit_sha: str
    branch_name: str
    language: str | None
    text: str
    dense_vector: List[List[float]]
    sparse_vector: List[Dict[int, float]]
    metadata: Metadata | None
    content_type: str | None

    def to_milvus_dict(self):
        """
        转换为 Milvus 所需的字典格式
        :return: Milvus 所需的字典格式
        """
        return {
            "user_id": self.user_id,
            "repository_id": self.repository_id,
            "file_path": self.file_path,
            "commit_sha": self.commit_sha,
            "branch_name": self.branch_name,
            "language": self.language or "",
            "text": self.text,
            "dense_vector": self.dense_vector,
            "sparse_vector": self.sparse_vector,
            "metadata": self.metadata.model_dump() if self.metadata else {},
            "content_type": self.content_type or ""
        }

    @classmethod
    def from_milvus_dict(cls, data):
        """
        从 Milvus 返回的数据中创建 MilvusPO 对象
        :param data: Milvus 返回的数据
        :return: MilvusPO 对象
        """
        return cls(
            id=data["id"],
            user_id=data["user_id"],
            repository_id=data["repository_id"],
            file_path=data["file_path"],
            commit_sha=data["commit_sha"],
            branch_name=data["branch_name"],
            language=data["language"],
            text=data["text"],
            dense_vector=data["dense_vector"],
            sparse_vector=data["sparse_vector"],
            content_type=data["content_type"],
            metadata=Metadata(**data["metadata"])
        )


@dataclass()
class RequestDTO:
    user_id: int
    repository_id: int
    branch_name: str
    commit_sha: str
    message: str
    commit_time: str
    merge_files: List[str]
    file_path: str
    status: str
    author: str
    pr_name: str

    @classmethod
    def from_dict(cls, data):
        return cls(
            user_id=data["user_id"],
            repository_id=data["repository_id"],
            branch_name=data["branch_name"],
            commit_sha=data["commit_sha"],
            message=data["message"],
            merge_files=data["merge_files"],
            file_path=data["file_path"],
            author=data["author"],
            status=data["status"],
            commit_time=data["commit_time"],
            pr_name=data["pr_name"],
        )


class IntentResult(BaseModel):
    intent: List[Literal["explain", "review", "archaeology", "search"]]
    confidence: float = Field(ge=0.0, le=1.0)
    reason: str = Field(description="简短的理由，用于日志和调试")

    # 可选字段：只在特定场景下使用
    need_clarify: bool = False

class DiffData(TypedDict):
    """
    diff_data 的 JSON 格式
    """
    file_path: str
    old_content: str
    new_content: str
    diff: str

class Context(TypedDict):
    """
    context 的 JSON 格式
    """
    pr_id: int
    pr_name: str
    diff_data: List[DiffData]

class ChatDTO(TypedDict):
    """
    chat 的 JSON 格式
    {
      "query": "解释一下这段改动",
      "owner": 1,
      "repo": 100,
      "context": {
        "pr_id": 999,                // 如果是 PR 场景
        "diff_data": [               // 前端传过来的结构化 Diff
          {
            "file_path": "OrderService.java",
            "old_content": "...",
            "new_content": "...",
            "diff": "..."
          }
        ],
      }
    }
    """
    query: str
    owner: int
    repo: int
    context: Context | None # 全局共享只写一次

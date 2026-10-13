from dataclasses import dataclass
from typing import List, Dict

from pymilvus import DataType

from schema.metadata import Metadata


@dataclass
class MilvusPO:
    id: int | None
    user_id: int
    repository_id: int
    file_path: str
    commit_sha: str
    branch_name: str
    language: str
    text: str
    dense_vector: List[List[float]]
    sparse_vector: List[Dict[int, float]]
    metadata: Metadata

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
            "language": self.language,
            "text": self.text,
            "dense_vector": self.dense_vector,
            "sparse_vector": self.sparse_vector,
            "metadata": self.metadata.model_dump()
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
            metadata=Metadata(**data["metadata"])
        )



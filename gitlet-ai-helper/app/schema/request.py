"""
请求体
"""
from dataclasses import dataclass

from schema.base_model import PullRequest


@dataclass
class Request:
    """
    请求体
    """
    # 用户ID
    user_id: int
    # 仓库ID
    repository_id: int
    # 分支名称
    branch_name: str
    # PR 信息
    pr_info: PullRequest

    # # 文件路径
    # file_path: str
    # # 提交SHA
    # commit_sha: str
    # # 作者
    # author: str
    # # 提交时间
    # commit_time: str

    def to_request_dict(self):
        """
        转换为请求字典
        :return: 请求字典
        """
        return {
            "user_id": self.user_id,
            "repository_id": self.repository_id,
            "branch_name": self.branch_name,
            "pr_info": self.pr_info.model_dump()
            # "file_path": self.file_path,
            # "commit_sha": self.commit_sha,
            # "author": self.author,
            # "commit_time": self.commit_time,
        }

    @classmethod
    def from_request(cls, data):
        """
        从请求数据中创建 Request 对象
        :param data: 请求数据
        :return: Request 对象
        """
        return cls(
            user_id=data["user_id"],
            repository_id=data["repository_id"],
            branch_name=data["branch_name"],
            pr_info=PullRequest(**data["pr_info"])
            # file_path=data["file_path"],
            # commit_sha=data["commit_sha"],
            # author=data["author"],
            # commit_time=data["commit_time"],
        )
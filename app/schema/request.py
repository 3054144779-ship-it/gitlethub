"""
请求体
"""
from dataclasses import dataclass

@dataclass
class Request:
    """
    请求体
    """
    # 用户ID
    user_id: int
    # 仓库ID
    repository_id: int
    # 文件路径
    file_path: str
    # 提交SHA
    commit_sha: str
    # 分支名称
    branch_name: str
    # 作者
    author: str
    # 提交时间
    commit_time: str

    def to_request_dict(self):
        """
        转换为请求字典
        :return: 请求字典
        """
        return {
            "user_id": self.user_id,
            "repository_id": self.repository_id,
            "file_path": self.file_path,
            "commit_sha": self.commit_sha,
            "branch_name": self.branch_name,
            "author": self.author,
            "commit_time": self.commit_time,
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
            file_path=data["file_path"],
            commit_sha=data["commit_sha"],
            branch_name=data["branch_name"],
            author=data["author"],
            commit_time=data["commit_time"],
        )
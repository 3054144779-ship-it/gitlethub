"""
metadata 的 JSON 格式
"""
from typing import List, Dict, TypedDict

from pydantic import BaseModel

class Metadata(BaseModel):
    """
    metadata 的 JSON 格式
    """
    # 方法名
    method_name: str | None = None
    # 开始行
    start_line: int
    # 结束行
    end_line: int
    # 分块索引
    chunk_index: int
    # 作者
    author: str
    # 提交时间
    commit_time: str

class TrackedFile(BaseModel):
    """
    tracked file 的 JSON 格式
    """
    file_path: str
    status: str

class CommitMessage(BaseModel):
    """
    commit message 的 JSON 格式
    """
    # 提交 SHA
    commit_sha: str
    # 提交信息
    message: str
    # 提交时间
    time: str
    # 合并文件
    merge_files: List[str]
    # 跟踪文件
    tracked_files: List[TrackedFile]


class PullRequest(BaseModel):
    """
    pull request 的 JSON 格式
    """
    # PR 名称
    pr_name: str
    # 作者
    author: str
    # 提交信息
    commits: List[CommitMessage]

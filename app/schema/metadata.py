"""
metadata 的 JSON 格式
"""
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
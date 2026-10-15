"""
定义节点类，用于表示代码中的节点
"""
from typing import List
from pydantic import BaseModel

class ArchaeologyState:
    """
    考古节点状态
    """

    query: str                 # 查询内容
    owner: int                 # owner
    repo: int                  # repo
    rewritten_query: str       # 重写后的查询内容
    code_docs: List[dict]      # code文档
    commit_docs: List[dict]    # commit文档
    reranked_docs: List[dict]  # rerank后的文档
    prompt: str                # prompt
    answer: str                # 答案
    is_valid: bool             # 是否有效
    retry_count: int           # 重试次数
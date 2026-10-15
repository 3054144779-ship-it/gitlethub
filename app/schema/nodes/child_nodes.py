"""
定义节点类，用于表示代码中的节点
"""
from typing import List
from schema.nodes.node import NodeState

class ArchaeologyState(NodeState):
    """
    考古节点状态
    """

    query: str                 # 查询内容
    owner: int                 # owner
    repo: int                  # repo
    rewritten_query: str       # 重写后的查询内容
    code_docs: List[dict]      # code文档
    commit_docs: List[dict]    # commit文档
    reranked_docs: dict        # rerank后的文档
    prompt: str                # prompt
    answer: str                # 答案
    # is_valid: bool             # 是否有效
    # retry_count: int           # 重试次数


async def archaeology(state: NodeState):
    """
    考古节点
    :param state: 考古节点状态
    :return: 考古节点状态
    """
    return {
        "intent": "archaeology",
    }

async def review(state: NodeState):
    """
    审查节点
    :param state: 审查节点状态
    :return: 审查节点状态
    """
    return {
        "intent": "review",
    }

async def search(state: NodeState):
    """
    搜索节点
    :param state: 搜索节点状态
    :return: 搜索节点状态
    """
    return {
        "intent": "search",
    }

async def explain(state: NodeState):
    """
    解释节点
    :param state: 解释节点状态
    :return: 解释节点状态
    """
    return {
        "intent": "explain",
    }


if __name__ == '__main__':
    pass






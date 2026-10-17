"""
定义节点类，用于表示代码中的节点
"""
from typing import List
from schema.nodes.node import NodeState

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
    print("state:", state.get("context", "上下文信息遗失"))
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
        "review_model": 1
    }


if __name__ == '__main__':
    pass






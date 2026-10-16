from dataclasses import dataclass
from typing import List, TypedDict, NotRequired

from dependency_injector.wiring import Provide, inject
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import SystemMessage, HumanMessage

from config.Container import Container
from core.intent_clarifier import intent_recognition
from schema.domain import IntentResult
from service.impl.func_service_impl import ArchaeologyServiceImpl

# 模块级：服务注册表
service_registry = {
    "explain": None,
    "archaeology": ArchaeologyServiceImpl(),
    "review": None,
    "search": None,
}

class NodeState(TypedDict):
    """
    节点状态
    """
    # 入口
    query: str
    owner: int
    repo: int

    # 节点逐步填充
    intent: NotRequired[str]        # 主意图
    intents: NotRequired[IntentResult] # 所有意图
    rewritten_query: NotRequired[str]
    code_docs: NotRequired[List[dict]]
    commit_docs: NotRequired[List[dict]]
    reranked_docs: NotRequired[dict]
    prompt: NotRequired[str]
    answer: NotRequired[str]

    # review流程独有
    review_model: NotRequired[int]       # 审查模式：1=debug, 2=基础审查, 3=深度审查
    diff_code: NotRequired[str]          # 差异代码（基础审查模式使用）
    review_answer: NotRequired[str]      # 审查结果
    review_files: NotRequired[List[str]] # 待审查文件列表
    commit_context: NotRequired[dict]    # 提交上下文（sha, message, author, branch, pr_name）


@inject
async def rewrite_query(state: NodeState, rewrite_llm: BaseChatModel = Provide[Container.rewrite_llm]):
    """
    重写查询节点
    :param rewrite_llm: 重写模型
    :param state: 考古节点状态
    :return: 考古节点状态
    """

    REWRITE_SYSTEM_MESSAGE = """你是一个查询重写助手。将用户问题改写为适合代码向量检索的关键词查询。
        ## 重写规则
        1. 去除口语化表达（"怎么"、"为什么"、"帮我看看"等），提取核心技术概念
        2. 补全隐含的技术术语（如"单例"→"singleton pattern"）
        3. 同时输出中文和英文两个查询，用换行分隔

        ## 输出格式
        严格输出两行，不要任何其他内容：
        中文查询
        英文查询

        ## 示例
        输入：这个类为什么改成单例模式了
        输出：
        单例模式 重构原因 设计模式
        singleton pattern refactoring design pattern

        输入：帮我找一下用户登录的接口在哪
        输出：
        用户登录 接口 认证 鉴权
        user login authentication authorization API
        """

    rewrite = await rewrite_llm.ainvoke([
        SystemMessage(content=REWRITE_SYSTEM_MESSAGE),
        HumanMessage(content=state["query"])
    ],
        extra_body={"enable_thinking": False}
    )

    return {
        "rewritten_query": rewrite.content,
    }


async def rag_search(state: NodeState):
    """
    RAG检索节点
    {
        "code_documents": code_docs,
        "commit_documents": commit_docs,
        "total_count": len(recall_docs)
    }
    """
    service = service_registry[state['intent']]

    result = await service.retrieval(
        query=state["rewritten_query"],
        owner=state["owner"],
        repo=state["repo"],
    )

    return {
        "code_docs": result["code_documents"],
        "commit_docs": result["commit_documents"],
    }


async def rerank_search(state: NodeState):
    """
    Rerank检索节点
    :param state: 考古节点状态
    :return: 考古节点状态
    """
    service = service_registry[state['intent']]

    result = await service.rerank(
        query=state["query"],
        code_docs=state["code_docs"],
        commit_docs=state["commit_docs"],
    )

    return {
        "reranked_docs": result,
    }


async def generate_prompt(state: NodeState):
    """
    生成prompt节点
    :param state: 考古节点状态
    :return: 考古节点状态
    """
    service = service_registry[state['intent']]

    prompt = await service.prompt(
        query=state["query"],
        retrieval_rerank_result=state["reranked_docs"],
        owner=state["owner"],
        repo=state["repo"],
    )

    return {
        "prompt": prompt,
    }


@inject
async def generate_answer(state: NodeState, agent=Provide[Container.agent]):
    """
    生成答案节点
    :param agent: 模型
    :param state: 考古节点状态
    :return: 考古节点状态
    """
    answer = await agent.ainvoke({
        "messages": [SystemMessage(content=state["prompt"])]
    })

    return {
        "answer": answer["messages"][-1].content,
    }

async def intent_node(state: NodeState):
    """
    意图识别节点（包装函数）
    """
    intents = await intent_recognition(state["query"])
    return {
        "intents": intents,
    }

async def intent_clarify(message: str):
    """
    意图澄清节点
    :param message: ai回复
    :return: 意图
    """
    return {
        "answer": message
    }
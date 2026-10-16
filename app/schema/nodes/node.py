import operator
from dataclasses import dataclass
from typing import List, TypedDict, NotRequired, Annotated

from dependency_injector.wiring import Provide, inject
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import SystemMessage, HumanMessage
from redis.asyncio import Redis

from config.Container import Container
from core.intent_clarifier import intent_recognition
from core.redis_key import prompt_to_redis_key
from schema.domain import IntentResult
from service.impl.func_service_impl import ArchaeologyServiceImpl, ExplainServiceImpl, ReviewServiceImpl

# 模块级：服务注册表
service_registry = {
    "explain": ExplainServiceImpl(),
    "archaeology": ArchaeologyServiceImpl(),
    "review": ReviewServiceImpl(),
    "search": None,
}

def _last_value(a, b):
    """取最后一个写入的值"""
    return b

class NodeState(TypedDict):
    """
    节点状态
    """
    # 入口
    query: str
    owner: int
    repo: int

    # 节点逐步填充（子图内隔离，无需前缀）
    intent: NotRequired[str]
    intents: NotRequired[IntentResult]
    rewritten_query: NotRequired[str]
    code_docs: NotRequired[List[dict]]
    commit_docs: NotRequired[List[dict]]
    reranked_docs: NotRequired[dict]
    prompt: NotRequired[str]
    answer: NotRequired[str]
    answers: NotRequired[Annotated[List[dict], operator.add]]  # 各子图结果聚合，结果追加

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
        4. 如果用户输入包含代码片段，从方法名、类名、变量名、注释中提取标识符，结合用户问题中的业务描述组合查询
        5. 禁止输出意图标签（如"代码考古"、"代码审查"、"代码解释"等），只输出技术检索关键词
        6. 不要推测代码行为，只基于代码中可见的标识符和用户描述进行改写
        
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
        
        输入：public void blacklist(String authorization) 这段代码做了什么
        输出：
        blacklist authorization 黑名单 令牌
        blacklist authorization token
        
        输入：ReviewServiceImpl 的 prompt 方法为什么用了 archaeology 模板
        输出：
        ReviewServiceImpl prompt 模板 引用
        ReviewServiceImpl prompt template reference
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
    service = service_registry[state["intent"]]  # 回归直读

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
    :param state: 节点状态
    :return: 节点状态
    """
    service = service_registry[state["intent"]]

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
    :param state: 节点状态
    :return: 节点状态
    """
    service = service_registry[state["intent"]]

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
    content = answer["messages"][-1].content

    return {
        "answer": content,
        "answers": [{"intent": state.get("intent", ""), "content": content}],  # 追加到聚合列表
    }

async def intent_node(state: NodeState):
    """
    意图识别节点（包装函数）
    """
    intents = await intent_recognition(state["query"])
    return {
        "intents": intents,
    }

async def intent_clarify(state: NodeState):
    """
    意图澄清节点
    :param state: 节点状态
    :return: 澄清回复
    """
    return {
        "answer": state["intents"].reason
    }

async def review_debug(state: NodeState):
    """
    代码审查节点(debug模式)
    :param state: 节点状态
    :return: 节点状态
    """
    service = service_registry["review"]
    # 生成prompt
    prompt = await service.prompt(
        query=state["query"],
        owner=state["owner"],
        repo=state["repo"],
        retrieval_rerank_result={}, # 临时解决
        model=state["review_model"],
    )

    return {
        "prompt": prompt
    }

@inject
async def review_generate(
        state: NodeState,
        redis_client: Redis = Provide[Container.redis_client]
):
    """
    代码审查节点，生成回复
    :param redis_client: redis客户端
    :param state: 节点状态
    :return: 节点状态
    """
    result = await generate_answer(state)
    ans = result.get("answer", "")
    # 将结果保存到redis
    await redis_client.set(name=prompt_to_redis_key(state["query"]), value=str(ans), ex=3600) # 1小时过期
    return {}

@inject
async def generate_explain_prompt(
        state: NodeState,
        redis_client: Redis = Provide[Container.redis_client]
):
    """
    生成prompt节点
    :param redis_client: redis客户端
    :param state: 节点状态
    :return: 节点状态
    """
    service = service_registry[state['intent']]

    raw = await redis_client.get(prompt_to_redis_key(state["query"]))
    review_answer = raw if raw else ""

    if not review_answer:
        review_answer = "代码审查结果为空，请在本次调用中提供代码审查结果"

    prompt = await service.prompt(
        query=state["query"],
        retrieval_rerank_result=state["reranked_docs"],
        owner=state["owner"],
        repo=state["repo"],
        review_answer=review_answer
    )

    return {
        "prompt": prompt,
    }
"""
定义节点类，用于表示代码中的节点
"""
from typing import List, TypedDict

from dependency_injector.wiring import Provide, inject
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import SystemMessage, HumanMessage
from pydantic import BaseModel

from config.Container import Container
from service.impl.func_service_impl import ArchaeologyServiceImpl
from client.llm_client import LLMClient
from config.Settings import Settings

# 创建考古服务模块级单例
archaeology_service = ArchaeologyServiceImpl()
# agent = LLMClient(
#     model=Settings.LLM_MODEL,
#     model_provider=Settings.LLM_MODEL_PROVIDER,
#     tools=None # TODO：后续添加工具
# ).create_agent()

class ArchaeologyState(TypedDict):
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

@inject
async def rewrite_query(state: ArchaeologyState, rewrite_llm: BaseChatModel = Provide[Container.rewrite_llm]):
    """
    重写查询节点
    :param rewrite_llm: 重写模型
    :param state: 考古节点状态
    :return: 考古节点状态
    """

    REWRITE_SYSTEM_MESSAGE = """
    你是一个查询重写助手。将用户问题改写为适合代码向量检索的关键词查询。
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
    ])

    return {
        "rewritten_query": rewrite.content,
    }



async def rag_search(state: ArchaeologyState):
    """
    RAG检索节点
    {
        "code_documents": code_docs,
        "commit_documents": commit_docs,
        "total_count": len(recall_docs)
    }
    """
    result = await archaeology_service.retrieval(
        query=state["rewritten_query"],
        owner=state["owner"],
        repo=state["repo"],
    )

    return {
        "code_docs": result["code_documents"],
        "commit_docs": result["commit_documents"],
    }

async def rerank_search(state: ArchaeologyState):
    """
    Rerank检索节点
    :param state: 考古节点状态
    :return: 考古节点状态
    """
    result = await archaeology_service.rerank(
        query=state["query"],
        code_docs=state["code_docs"],
        commit_docs=state["commit_docs"],
    )

    return {
        "reranked_docs": result,
    }

async def generate_prompt(state: ArchaeologyState):
    """
    生成prompt节点
    :param state: 考古节点状态
    :return: 考古节点状态
    """
    prompt = await archaeology_service.prompt(
        query=state["query"],
        retrieval_rerank_result=state["reranked_docs"],
        owner=state["owner"],
        repo=state["repo"],
    )

    return {
        "prompt": prompt,
    }

@inject
async def generate_answer(state: ArchaeologyState, agent = Provide[Container.agent]):
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


if __name__ == '__main__':
    pass






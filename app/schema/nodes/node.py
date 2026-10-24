import json
import logging
import operator
from dataclasses import dataclass
from typing import List, TypedDict, NotRequired, Annotated, Dict, Any

from dependency_injector.wiring import Provide, inject
from langchain.agents import AgentState
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import SystemMessage, HumanMessage, AnyMessage, AIMessageChunk
from langgraph.config import get_stream_writer
from redis.asyncio import Redis

from config.Container import Container
from core.intent_clarifier import intent_recognition
from core.memory_manager import MemoryManager
from core.procedure_store import ProcedureStore
from core.redis_key import prompt_to_redis_key
from schema.domain import IntentResult, Context
from service.impl.archaeology_service_impl import ArchaeologyServiceImpl
from service.impl.explain_service_impl import ExplainServiceImpl
from service.impl.review_serivce_impl import ReviewServiceImpl
from service.impl.semantic_search_service_impl import SearchServiceImpl

# 模块级：服务注册表
service_registry = {
    "explain": ExplainServiceImpl(),
    "archaeology": ArchaeologyServiceImpl(),
    "review": ReviewServiceImpl(),
    "search": SearchServiceImpl(),
}

def _last_value(a, b):
    """取最后一个写入的值"""
    return b

class NodeState(TypedDict):
    """
    节点状态
    """
    # 入口
    session_id: str
    query: str
    owner: int
    repo: int
    context: Context | None # 上下文信息(可以不存在)

    # 节点逐步填充（子图内隔离，无需前缀）
    intent: NotRequired[str]
    intents: NotRequired[IntentResult]
    rewritten_query: NotRequired[str]
    code_docs: NotRequired[List[dict]]
    commit_docs: NotRequired[List[dict]]
    reranked_docs: NotRequired[Annotated[dict, operator.or_]]
    prompt: NotRequired[str]
    answer: NotRequired[str]
    answers: NotRequired[Annotated[List[dict], operator.add]]  # 各子图结果聚合，结果追加
    llm_messages: NotRequired[Annotated[List[AnyMessage], operator.add]] # 节点消息
    history_messages: NotRequired[List[str]] # 历史会话消息

    # review流程独有
    review_model: NotRequired[int]       # 审查模式：1=debug（快速审查）, 2=深度审查
    # diff_code: NotRequired[str]          # 差异代码（基础审查模式使用）
    # review_answer: NotRequired[str]      # 审查结果
    review_files: NotRequired[List[str]] # 待审查文件列表
    # commit_context: NotRequired[dict]    # 提交上下文（sha, message, author, branch, pr_name）
    influence_docs: NotRequired[List[dict]] # 影响文档列表
    history_docs: NotRequired[List[dict]] # 历史文档列表
    repository_consistency_docs: NotRequired[List[dict]] # 仓库一致性文档列表
    docs_name: NotRequired[str]


@inject
async def load_history(
        state: NodeState,
        memory_manager: MemoryManager = Provide[Container.memory_manager],
):
    history_messages = await memory_manager.get_history_messages(state["session_id"], state["query"])
    text = "\n".join(filter(
        None,
        [
            history_messages.get("milvus_messages", ""),
            history_messages.get("redis_messages", "")
        ]
    ))
    return {
        "history_messages": [text],
    }

@inject
async def rewrite_query(
        state: NodeState,
        rewrite_llm: BaseChatModel = Provide[Container.rewrite_llm],
        procedure_store: ProcedureStore = Provide[Container.procedure_store],

):
    """
    重写查询节点
    :param procedure_store: 存储器
    :param rewrite_llm: 重写模型
    :param state: 节点状态
    :return: 重写后的查询信息
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

    CORRECT_SYSTEM_MESSAGE = """你是一个“纠错信号识别器”。判断用户这句话是否在【纠正/补充/否定】上一轮助手的输出；若是，把这次纠正抽象成一条可复用的规则，供程序性记忆沉淀。

        ## 判定为“纠正”的情形（满足任一）
        1. 指正遗漏：上一轮漏掉了用户认为必须覆盖的内容（如“你怎么没看 SQL 注入”）
        2. 否定结论：不认可上一轮的判断或结论（如“这个不算问题”“你判断错了”）
        3. 补充要求：追加本次/后续任务应遵循的要求（如“以后都按这个格式输出”）
        4. 侧重/格式反馈：指出应更重视某方面或调整输出（如“太啰嗦了，精简点”“先说安全再说性能”）

        ## 不算“纠正”的情形（一律 is_correction=false）
        - 开启一个与原任务无关的新问题
        - 只是追问细节、希望展开说明（没有否定或指正）
        - 单纯称赞或确认（如“好的”“可以”“谢谢”）
        - 信息不足、无法判断

        ## 抽取规则的要求（仅当 is_correction=true）
        - 用一句话、第三人称、祈使语气描述“以后遇到同类任务应当怎么做”
        - 必须去掉一次性细节：具体文件名、变量名、本次报错原文、具体行号
        - 保留可长期复用的技术要点（如“必须检查 SQL 注入”“输出控制在 300 字内”）
        - 若该纠正只对本次成立、不具备复用价值，则 is_correction=false 且 rule 为空字符串

        ## scope 判定
        - user：与某个人的偏好/习惯相关（输出风格、语言、详略程度）
        - repo：与某个仓库/项目/技术栈相关（如“本项目用 MyBatis，需防 ${} 注入”）
        - global：通用工程实践，不依赖具体人或仓库

        ## 输出格式
        只输出一个 JSON 对象，不要 markdown 代码块、不要任何解释文字：
        {"is_correction": true, "target_intent": "review", "scope": "repo", "rule": "审查时必须检查 SQL 注入风险"}

        无纠正时输出：
        {"is_correction": false, "target_intent": "unknown", "scope": "global", "rule": ""}

        ## 字段说明
        - target_intent：本次纠正针对的功能，取值 explain/review/archaeology/search/unknown
        - scope：规则应沉淀到哪一层作用域
        - rule：可复用的祈使句规则，无则为空字符串
    """

    # 重写用户问题
    rewrite = await rewrite_llm.ainvoke([
        SystemMessage(content=REWRITE_SYSTEM_MESSAGE),
        HumanMessage(content=state["query"])
    ],
        extra_body={"enable_thinking": False}
    )


    history = state.get("history_messages", [])
    if history:
        previous_answer = history[-1]

        # 纠正信号识别
        correct = await rewrite_llm.ainvoke(
            [
                SystemMessage(content=CORRECT_SYSTEM_MESSAGE),
                HumanMessage(content=f"上一轮的输出：{previous_answer}, 这一轮的输入: {state["query"]}")
            ],
            extra_body={"enable_thinking": False}
        )

        """
        {
            "is_correction": true, 
            "target_intent": "review", 
            "scope": "repo", 
            "rule": "审查时必须检查 SQL 注入风险"
        }
        """
        signal = {}
        try:
            signal = json.loads(str(correct.content))
        except json.JSONDecodeError:
            logging.warning("ai信息识别结构生成失败")


        if signal.get("is_correction") and signal.get("rule"):
            await procedure_store.append(
                intent=state.get("intent", "unknown"),
                procedure=signal["rule"],
                scope=signal["scope"], # user/repo/global 作用域
                scope_id=state["owner"] if signal["scope"] == "user"
                                        else state["repo"]
                                        if signal["scope"] == "repo"
                                        else None # 作用域唯一id
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
        context=state.get("context", None),
    )

    # 构建更新字段
    update_field = ["code_docs", "commit_docs", "influence_docs", "history_docs", "repository_consistency_docs"]

    update_field_dict: Dict[str, Any] = {name : result.get(name) for name in update_field if result.get(name, None)}

    return update_field_dict


async def rerank_search(state: NodeState):
    """
    Rerank检索节点
    :param state: 节点状态
    :return: 节点状态
    """
    service = service_registry[state["intent"]]

    result = await service.rerank(
        query=state["query"],
        code_docs=state.get("code_docs", []),
        commit_docs=state.get("commit_docs", []),
    )

    return {
        "reranked_docs": result,
    }

async def rerank_review_search(state: NodeState):
    """
    Rerank检索节点（历史记录）
    :param state: 节点状态
    :return: 节点状态
    """
    service = service_registry[state["intent"]]
    docs_name = state["docs_name"]
    result = await service.rerank(
        query=state["query"],
        docs_name=docs_name,
        docs=state[docs_name],
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
        context=state["context"],
    )

    return {
        "prompt": prompt,
    }

@inject
async def generate_answer(state: NodeState, agent=Provide[Container.agent], *, forward: bool = True):
    """
    生成答案节点
    :param agent: 模型
    :param state: 节点状态
    :param forward: 是否把 token 冒泡给外层流（explain 内部的中间审查步骤传 False）
    :return: 生成回复
    """
    writer = get_stream_writer()
    intent = state.get("intent", "")
    parts = []
    final_message = None

    async for chunk, _meta in agent.astream(
            {
                "messages": [
                    SystemMessage(content=f"以下是历史消息{state.get('history_messages', [])}"),
                    HumanMessage(content=state["prompt"]),
                ],
                "session_id": state["session_id"],
                "query": state["query"],
            },
            stream_mode="messages",
    ):
        # 只取模型生成的文本，过滤 ToolMessage / 纯工具调用片段
        if not isinstance(chunk, AIMessageChunk):
            continue
        final_message = chunk if final_message is None else final_message + chunk
        text = chunk.content if isinstance(chunk.content, str) else ""
        if not text:
            continue
        parts.append(text)
        if forward:
            writer({"intent": intent, "text": text})

    content = "".join(parts)

    return {
        "answer": content,
        "answers": [{"intent": intent, "content": content}],
        "llm_messages": [final_message] if final_message is not None else [],
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
    message = f"历史消息：{state.get('history_messages', [])}， 当前消息：{state["query"]}"
    # 生成prompt
    prompt = await service.prompt(
        query=message,
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
    result = await generate_answer(state, forward=False)
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
    review_answer = raw.decode("utf-8") if raw else ""

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
from langgraph.constants import END, START
from langgraph.graph import StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Send

from schema.nodes.child_nodes import explain, archaeology, review, search
from schema.nodes.node import (
    NodeState, rewrite_query, rag_search, rerank_search, generate_prompt,
    generate_answer, intent_clarify, intent_node, review_debug,
    review_generate, generate_explain_prompt, rerank_review_search,
)

def build_explain_subgraph():
    """
    explain 子图：RAG 管道 + 内部 review 分支
    explain -> rewrite_query -> rag_search -> rerank_search ──┐
            -> review_debug -> review_generate ───────────────┴-> generate_explain_prompt -> generate_answer
    """
    builder = StateGraph(NodeState)  # type: ignore
    builder.add_node("explain", explain)  # type: ignore
    builder.add_node("rewrite_query", rewrite_query)  # type: ignore
    builder.add_node("rag_search", rag_search)  # type: ignore
    builder.add_node("rerank_search", rerank_search)  # type: ignore
    # defer = True 表示生成节点的执行将被推迟，直到所有依赖的节点都完成
    builder.add_node("generate_explain_prompt", generate_explain_prompt, defer=True)  # type: ignore
    builder.add_node("generate_answer", generate_answer)  # type: ignore
    builder.add_node("review_debug", review_debug)  # type: ignore
    builder.add_node("review_generate", review_generate)  # type: ignore

    builder.add_edge(START, "explain")
    builder.add_edge("explain", "rewrite_query")
    builder.add_edge("explain", "review_debug")
    builder.add_edge("review_debug", "review_generate")
    builder.add_edge("review_generate", "generate_explain_prompt")
    builder.add_edge("rewrite_query", "rag_search")
    builder.add_edge("rag_search", "rerank_search")
    builder.add_edge("rerank_search", "generate_explain_prompt")
    builder.add_edge("generate_explain_prompt", "generate_answer")
    builder.add_edge("generate_answer", END)
    return builder.compile()


def build_archaeology_subgraph():
    """
    archaeology 子图：复用同一套管道节点函数
    archaeology -> rewrite_query -> rag_search -> rerank_search -> generate_prompt -> generate_answer
    """
    builder = StateGraph(NodeState)  # type: ignore
    builder.add_node("archaeology", archaeology)  # type: ignore
    builder.add_node("rewrite_query", rewrite_query)  # type: ignore
    builder.add_node("rag_search", rag_search)  # type: ignore
    builder.add_node("rerank_search", rerank_search)  # type: ignore
    builder.add_node("generate_prompt", generate_prompt)  # type: ignore
    builder.add_node("generate_answer", generate_answer)  # type: ignore

    builder.add_edge(START, "archaeology")
    builder.add_edge("archaeology", "rewrite_query")
    builder.add_edge("rewrite_query", "rag_search")
    builder.add_edge("rag_search", "rerank_search")
    builder.add_edge("rerank_search", "generate_prompt")
    builder.add_edge("generate_prompt", "generate_answer")
    builder.add_edge("generate_answer", END)
    return builder.compile()

def build_review_subgraph():
    """
    review 子图：复用同一套管道节点函数（除了rerank_review_search）
    review -> rewrite_query -> rag_search[influence_docs, history_docs, repository_consistency_docs]
    -> rerank_search[influence_docs, history_docs, repository_consistency_docs 并行] ->
    generate_prompt -> generate_answer
    """

    builder = StateGraph(NodeState)
    builder.add_node("review", review)
    builder.add_node("rewrite_query", rewrite_query)
    builder.add_node("rag_search", rag_search)
    builder.add_node("rerank_review_search", rerank_review_search)
    builder.add_node("generate_prompt", generate_prompt, defer=True)
    builder.add_node("generate_answer", generate_answer)

    builder.add_edge(START, "review")
    builder.add_edge("review", "rewrite_query")
    builder.add_edge("rewrite_query", "rag_search")

    # 条件路由
    def fan_out(state: NodeState):
        return [
            Send(
                "rerank_review_search",
                {
                    "query": state["query"],
                    "intent": state["intent"],
                    "docs_name": name,
                    name: state.get(name, [])
                },
            )
            for name in ["influence_docs", "history_docs", "repository_consistency_docs"]
        ]

    # 添加条件边
    builder.add_conditional_edges(
        "rag_search",
        fan_out,
        ["rerank_review_search"],
    )

    builder.add_edge("rerank_review_search", "generate_prompt")
    builder.add_edge("generate_prompt", "generate_answer")
    builder.add_edge("generate_answer", END)

    return builder.compile()

def build_search_subgraph():
    """
    search 子图：复用同一套管道节点函数
    search -> rewrite_query -> rag_search -> rerank_search -> generate_prompt -> generate_answer
    """
    builder = StateGraph(NodeState)

    builder.add_node("search", search)
    builder.add_node("rewrite_query", rewrite_query)
    builder.add_node("rag_search", rag_search)
    builder.add_node("rerank_search", rerank_search)
    builder.add_node("generate_prompt", generate_prompt)
    builder.add_node("generate_answer", generate_answer)

    builder.add_edge(START, "search")
    builder.add_edge("search", "rewrite_query")
    builder.add_edge("rewrite_query", "rag_search")
    builder.add_edge("rag_search", "rerank_search")
    builder.add_edge("rerank_search", "generate_prompt")
    builder.add_edge("generate_prompt", "generate_answer")
    builder.add_edge("generate_answer", END)

    return builder.compile()

class GitletGraph:
    """
    Gitlet主图：只做意图路由和子图分发
    START -> intent_node -> fan_out -> [explain_subgraph / archaeology_subgraph / review_subgraph] -> END
                           -> intent_clarify -> END
    """

    def __init__(self):
        self.builder = StateGraph(state_schema=NodeState)  # type: ignore

    def build_graph(self) -> CompiledStateGraph:
        # 主图节点
        self.builder.add_node("intent_node", intent_node)  # type: ignore
        self.builder.add_node("intent_clarify", intent_clarify)  # type: ignore
        # 子图节点
        self.builder.add_node("explain_subgraph", build_explain_subgraph())
        self.builder.add_node("archaeology_subgraph", build_archaeology_subgraph())
        self.builder.add_node("review_subgraph", build_review_subgraph())
        self.builder.add_node("search_subgraph", build_search_subgraph())

        self.builder.add_edge(START, "intent_node")
        self.builder.add_edge("intent_clarify", END)

        # 条件路由
        def fan_out(state: NodeState):
            intents = state['intents'].intent

            if not intents or len(intents) <= 0:
                # 未识别到意图，进行意图澄清
                return [Send("intent_clarify", {"query": state["query"], "intents": state["intents"]})]

            # explain 内部已包含 review，过滤掉避免重复执行
            if "explain" in intents:
                intents = [i for i in intents if i != "review"]

            # 每个子图收到独立的 state 副本，intent 写在 payload 里，天然隔离
            return [
                Send(f"{intent}_subgraph", {
                    "query": state["query"],
                    "owner": state["owner"],
                    "repo": state["repo"],
                    "context": state["context"],
                    "intent": intent,
                })
                for intent in intents
            ]

        self.builder.add_conditional_edges(
            "intent_node",
            fan_out,
            ["explain_subgraph", "archaeology_subgraph", "review_subgraph", "search_subgraph", "intent_clarify"],
        )

        return self.builder.compile()

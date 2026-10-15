from langgraph.constants import END, START
from langgraph.graph import StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Send

from core.intent_clarifier import intent_recognition
from schema.nodes.child_nodes import review, search, explain, archaeology
from schema.nodes.node import NodeState, rewrite_query, rag_search, rerank_search, generate_prompt, generate_answer, \
    intent_clarify


class GitletGraph:
    """
    Gitlet流程图
    """
    def __init__(self):
        self.builder = StateGraph(state_schema=NodeState) # type: ignore

    def _add_nodes(self):
        """
        添加节点
        """
        # 添加节点
        # 分支节点
        self.builder.add_node("review", review) # type: ignore
        self.builder.add_node("search", search) # type: ignore
        self.builder.add_node("explain", explain) # type: ignore
        self.builder.add_node("archaeology", archaeology) # type: ignore
        # 流程节点
        self.builder.add_node("intent_recognition", intent_recognition) # type: ignore
        self.builder.add_node("rewrite_query", rewrite_query) # type: ignore
        self.builder.add_node("rag_search", rag_search) # type: ignore
        self.builder.add_node("rerank_search", rerank_search) # type: ignore
        self.builder.add_node("generate_prompt", generate_prompt) # type: ignore
        self.builder.add_node("generate_answer", generate_answer) # type: ignore
        self.builder.add_node("intent_clarify", intent_clarify) # type: ignore

    def _add_archaeology_edges(self):
        """
        添加考古节点边
        intent_recognition -> archaeology -> rewrite_query -> rag_search -> rerank_search -> generate_prompt -> generate_answer
        """
        self.builder.add_edge("intent_recognition", "archaeology")
        self.builder.add_edge("archaeology", "rewrite_query")
        self.builder.add_edge("rewrite_query", "rag_search")
        self.builder.add_edge("rag_search", "rerank_search")
        self.builder.add_edge("rerank_search", "generate_prompt")
        self.builder.add_edge("generate_prompt", "generate_answer")
        self.builder.add_edge("generate_answer", END)

    def _add_search_edges(self):
        """
        添加搜索节点边
        intent_recognition -> search -> rewrite_query -> rag_search -> rerank_search -> generate_prompt -> generate_answer
        """
        pass

    def _add_review_edges(self):
        """
        添加审查节点边
        intent_recognition -> review -> rewrite_query -> rag_search -> rerank_search -> generate_prompt -> generate_answer
        """
        pass

    def _add_explain_edges(self):
        """
        添加解释节点边
        intent_recognition -> explain -> rewrite_query -> rag_search -> rerank_search -> generate_prompt -> generate_answer
        """
        pass

    def build_graph(self) -> CompiledStateGraph:
        """
        构建节点
        :return: 流程图
                                            -> intent_clarify -> END
        query(START)  -> intent_recognition -> rewrite_query -> rag_search -> rerank_search -> generate_prompt -> generate_answer -> END
                                            -> ...
        """
        # 创建状态图
        # 创建节点
        self._add_nodes()
        # 构建子图
        self._add_archaeology_edges()
        self._add_search_edges()
        self._add_review_edges()
        self._add_explain_edges()

        self.builder.add_edge(START, "intent_recognition")
        self.builder.add_node("intent_clarify", END)

        # 条件路由
        def fan_out(state: NodeState):
            # 获取意图
            intents = state['intents'].intent

            if intents or len(intents) <= 0:
                # 如果说未识别到意图，则进行意图澄清
                return [Send("intent_clarify", state['intents'].reason)]

            # 如果说识别到意图，则进行意图处理，可以多个意图并行
            return [Send(intent, state) for intent in intents]

        self.builder.add_conditional_edges("intent_recognition", fan_out)

        # 编译图，生成流程图
        return self.builder.compile()

import redis.asyncio as aioredis

from langgraph.graph.state import CompiledStateGraph
from pymilvus import MilvusClient
from dependency_injector import containers, providers

from client.memory_manager_middleware import MemoryManagerMiddleware
from core.memory_manager import MemoryManager
from core.tools import read_file
from client.llm_client import LLMClient
from core.reranker import Reranker
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel
from jinja2 import Environment, FileSystemLoader, select_autoescape, Template
from langchain.chat_models import init_chat_model

def _create_milvus_client(host: str, port: int) -> MilvusClient:
    return MilvusClient(uri=f"http://{host}:{port}")

def _init_jinja_env(template_dir: str) -> Environment:
    return Environment(
        loader=FileSystemLoader(template_dir),
        autoescape=select_autoescape(["html", "xml"]),
        trim_blocks=True,
        lstrip_blocks=True,
    )

def _load_template(jinja_env: Environment, template_name: str) -> Template:
    return jinja_env.get_template(template_name)

def _init_agent(
        model: str,
        model_provider: str,
        api_key: str,
        base_url: str,
        middleware_instance: MemoryManager
):
    # 初始化中间件
    memory_manager_middleware = MemoryManagerMiddleware(memory_manager=middleware_instance)
    middleware = [memory_manager_middleware]
    return LLMClient(
        model=model,
        model_provider=model_provider,
        config={
            "api_key": api_key,
            "base_url": base_url,
        },
        tools=[read_file]
    ).create_agent(
        middleware=middleware
    )

def _build_graph():
    # 执行的时候导入GitletGraph类，防止循环导入
    from core.graph import GitletGraph
    graph = GitletGraph()
    return graph.build_graph()

class Container(containers.DeclarativeContainer):
    """
    容器类，用于依赖注入
    """
    # 配置提供，从环境变量中读取配置
    config = providers.Configuration()
    # MilvusRepo提供，使用Singleton模式，配置从config中读取
    # 创建MilvusClient实例
    milvus_client = providers.Singleton(
        _create_milvus_client,
        host=config.milvus.host,
        port=config.milvus.port,
    )

    # 创建MilvusRepo实例
    milvus_repo = providers.Singleton(
        MilvusRepo,
        milvus_client=milvus_client,
        collection_name=config.milvus.collection_name,
        fields=config.milvus.fields,
        index_params=config.milvus.index_params
    )

    # 创建EmbeddingModel实例
    embedding_model = providers.Singleton(
        EmbeddingModel,
        model_name_or_path=config.embedding.model_name_or_path,
        device=config.embedding.device
    )

    # 创建jinja2环境
    jinja_env = providers.Singleton(
        _init_jinja_env,
        template_dir=config.jinja.template_dir
    )

    # 创建提交信息单例模板
    commit_message_template = providers.Singleton(
        _load_template,
        jinja_env=jinja_env,
        template_name="commit_message_template.jinja2"
    )

    # 创建意图澄清单例模板
    intent_clarifier_template = providers.Singleton(
        _load_template,
        jinja_env=jinja_env,
        template_name="intent_clarifier_prompt_template.jinja2"
    )

    # 创建意图识别器ai
    intent_clarifier_llm_client = providers.Singleton(
        init_chat_model,
        model=config.intent_clarifier.model,
        model_provider=config.intent_clarifier.model_provider,
        api_key=config.intent_clarifier.api_key,
        base_url=config.intent_clarifier.base_url
    )

    # 创建reranker实例
    reranker = providers.Singleton(
        Reranker,
        model_name_or_path=config.reranker.model_name_or_path,
        use_fp16=config.reranker.use_fp16,
        device=config.reranker.device
    )

    # 创建代码考古功能单例模板
    code_archaeology_template = providers.Singleton(
        _load_template,
        jinja_env=jinja_env,
        template_name="archaeology_prompt_template.jinja2"
    )

    # 用户消息重写ai
    rewrite_llm = providers.Singleton(
        init_chat_model,
        model=config.rewrite.model,
        model_provider=config.rewrite.model_provider,
        api_key=config.rewrite.api_key,
        base_url=config.rewrite.base_url
    )

    # 回复agent
    agent = providers.Singleton(
        _init_agent,
        model=config.agent.model,
        model_provider=config.agent.model_provider,
        api_key=config.agent.api_key,
        base_url=config.agent.base_url
    )

    # 创建流程图
    flow_chart = providers.Singleton(
        _build_graph
    )

    # 创建redis实例
    redis_client = providers.Singleton(
        aioredis.Redis,
        host=config.redis.host,
        port=config.redis.port,
        db=config.redis.db,
        password=config.redis.password
    )

    # 创建代码审查功能单例模板
    code_review_template = providers.Singleton(
        _load_template,
        jinja_env=jinja_env,
        template_name="review_prompt_template.jinja2"
    )

    # 创建代码解释功能单例模板
    code_explain_template = providers.Singleton(
        _load_template,
        jinja_env=jinja_env,
        template_name="explain_prompt_template.jinja2"
    )

    # 创建代码搜索功能单例模板
    code_search_template = providers.Singleton(
        _load_template,
        jinja_env=jinja_env,
        template_name="semantic_search_prompt_template.jinja2",
    )

    # 用于文本压缩的模型
    compressor_llm = providers.Singleton(
        init_chat_model,
        model=config.compressor.model,
        model_provider=config.compressor.model_provider,
        api_key=config.compressor.api_key,
        base_url=config.compressor.base_url
    )

    # 记忆分层管理器
    memory_manager = providers.Singleton(
        MemoryManager,
        redis_client=redis_client,
        milvus_client=milvus_client,
        compress_client=compressor_llm,
        embedding_client=embedding_model,
    )

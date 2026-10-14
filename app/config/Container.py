from pymilvus import MilvusClient
from dependency_injector import containers, providers

from client.llm_client import LLMClient
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel
from jinja2 import Environment, FileSystemLoader, select_autoescape
from jinja2 import Template
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
    embeddingModel = providers.Singleton(
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




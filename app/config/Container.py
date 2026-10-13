from pymilvus import MilvusClient
from dependency_injector import containers, providers
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel

class Container(containers.DeclarativeContainer):
    """
    容器类，用于依赖注入
    """
    # 配置提供，从环境变量中读取配置
    config = providers.Configuration()
    # MilvusRepo提供，使用Singleton模式，配置从config中读取
    # 创建MilvusClient实例
    milvus_client = MilvusClient(
        uri=f"http://{config.milvus.host}:{config.milvus.port}"
    )

    # 创建MilvusRepo实例
    milvus_repo = providers.Singleton(
        MilvusRepo,
        milvus_client=milvus_client,
        fields=config.milvus.fields,
        index_params=config.milvus.index_params
    )

    # 创建EmbeddingModel实例
    embeddingModel = providers.Singleton(
        EmbeddingModel,
        model_name=config.embedding.model_name,
        model_path=config.embedding.model_path
    )


"""
Milvus工具
"""
from typing import List, Any, Dict, Optional

from pymilvus import MilvusClient, DataType, RRFRanker, AnnSearchRequest
import logging




class MilvusRepo:
    """Milvus工具("http://192.168.12.168:19530")"""
    def __init__(
            self,
            milvus_client: MilvusClient,
            collection_name: str,
            fields: List[Dict[str, Any]],
            index_params: Dict[str, Any] = None
    ):
        """
        初始化
        :param milvus_client: Milvus客户端
        :param collection_name: 集合名称
        :param fields: 字段列表
        :param index_params: 索引参数
        """
        self.milvus_client = milvus_client
        self.collection_name = collection_name
        self.fields = fields
        self.index_params = index_params or {}

    def create_collection(self):
        """创建集合"""
        if self.milvus_client.has_collection(self.collection_name):
            logging.info(f"集合 {self.collection_name} 已存在")
            return

        self.milvus_client.create_collection(
            collection_name=self.collection_name,
            schema=self._generate_schema(),
            index_params=self._generate_index_params()
        )
        logging.info(f"集合 {self.collection_name} 创建成功")

    def _generate_schema(self):
        # 生成集合结构，并且启动自动字段
        schema = self.milvus_client.create_schema(auto_id=True, enable_dynamic_field=True)

        for field in self.fields:
            field_name = field["name"]
            field_type = field["type"]
            # 处理不同类型的字段
            if field_type == DataType.FLOAT_VECTOR:
                # 添加稠密向量字段
                schema.add_field(
                    field_name=field_name,
                    datatype=field_type,
                    dim=field.get("dim", 1024),
                    is_primary=field.get("is_primary", False)
                )
            elif field_type == DataType.SPARSE_FLOAT_VECTOR:
                # 添加稀疏向量字段
                schema.add_field(
                    field_name=field_name,
                    datatype=field_type,
                    is_primary=field.get("is_primary", False)
                )
            elif field_type == DataType.VARCHAR:
                # 添加字符串字段
                schema.add_field(
                    field_name=field_name,
                    datatype=field_type,
                    max_length=field.get("max_length", 65535),
                    is_primary=field.get("is_primary", False)
                )
            else:
                # 添加其他类型字段
                schema.add_field(
                    field_name=field_name,
                    datatype=field_type,
                    is_primary=field.get("is_primary", False)
                )
        return schema

    def _generate_index_params(self):
        """生成索引参数"""
        index_param = self.milvus_client.prepare_index_params()
        # 遍历索引参数
        for field_name, config in self.index_params.items():
            index_param.add_index(
                field_name=field_name, # 字段名字
                index_type=config.get("index_type"), # 索引类型
                metric_type=config.get("metric_type", "COSINE"), # 度量类型
                params=config.get("params", {}) # 参数
            )
        return index_param

    def insert(self, data: List[Dict[str, Any]]) -> dict:
        """插入数据"""
        return self.milvus_client.insert(
            collection_name=self.collection_name,
            data=data
        )

    def retrieval(
            self,
            dense_anns_field: str,
            sparse_anns_field: str,
            dense_vector: Optional[List[float]] = None,
            sparse_vector: Optional[Dict[int, float]] = None,
            top_k: int = 10,
            ranker_k: int = 60,
            output_fields: List[str] = None
    ) -> List[List[Dict]]:
        """
        检索
        :param dense_anns_field: 稠密向量字段名
        :param sparse_anns_field: 稀疏向量字段名
        :param dense_vector: 稠密向量
        :param sparse_vector: 稀疏向量
        :param top_k: 返回结果数量
        :param ranker_k: RRF 排序参数
        :param output_fields: 输出字段
        :return: 检索结果
        """
        output_fields = output_fields or ["content"]

        if dense_vector is not None and sparse_vector is not None:
            """混合检索：稠密向量 + 稀疏向量"""
            dense_req = AnnSearchRequest(
                data=[dense_vector],
                anns_field=dense_anns_field,
                param={"metric_type": "HNSW"},
                limit=top_k,
            )
            sparse_req = AnnSearchRequest(
                data=[sparse_vector],
                anns_field=sparse_anns_field,
                param={"metric_type": "IP"},
                limit=top_k,
            )
            return self.milvus_client.hybrid_search(
                collection_name=self.collection_name,
                reqs=[dense_req, sparse_req],
                ranker=RRFRanker(k=ranker_k),
                limit=top_k,
                output_fields=output_fields
            )
        elif dense_vector is not None:
            """仅稠密向量检索"""
            return self.milvus_client.search(
                collection_name=self.collection_name,
                data=[dense_vector],
                anns_field=dense_anns_field,
                param={"metric_type": "HNSW"},
                limit=top_k,
                output_fields=output_fields
            )
        elif sparse_vector is not None:
            """仅稀疏向量检索"""
            return self.milvus_client.search(
                collection_name=self.collection_name,
                data=[sparse_vector],
                anns_field=sparse_anns_field,
                param={"metric_type": "IP"},
                limit=top_k,
                output_fields=output_fields
            )
        else:
            raise ValueError("至少需要提供一个向量参数")


if __name__ == '__main__':
    pass
    # milvus_client = MilvusClient("http://192.168.12.168:19530")
    #
    # fields = [
    #     {"name": "id", "type": DataType.INT64, "is_primary": True},
    #     {"name": "content", "type": DataType.VARCHAR, "max_length": 65535},
    #     {"name": "dense_vector", "type": DataType.FLOAT_VECTOR, "dim": 1024},
    #     {"name": "sparse_vector", "type": DataType.SPARSE_FLOAT_VECTOR},
    # ]
    #
    # index_params = {
    #     "dense_vector": {
    #         "index_type": "IVF_FLAT",
    #         "metric_type": "COSINE",
    #         "params": {"nlist": 1024}
    #     },
    #     "sparse_vector": {
    #         "index_type": "SPARSE_INVERTED_INDEX",
    #         "metric_type": "IP"
    #     }
    # }
    #
    # milvus_repo = MilvusRepo(milvus_client, "test_collection", fields, index_params)
    # milvus_repo.create_collection()


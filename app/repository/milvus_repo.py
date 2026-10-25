"""
Milvus工具
"""
import logging
from typing import List, Any, Dict, Optional

from pymilvus import (
    MilvusClient,
    DataType,
    Function,
    FunctionType,
    RRFRanker,
    AnnSearchRequest,
)

from config.Settings import Settings


class MilvusRepo:
    """Milvus工具("http://192.168.12.168:19530")"""

    def __init__(
            self,
            collection_name: str,
            milvus_client: MilvusClient,
            fields: List[Dict[str, Any]],
            index_params: Dict[str, Any] | None = None,
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
        self._schema_checked = False

    def create_collection(self):
        """创建集合"""
        if self.milvus_client.has_collection(self.collection_name):
            self._check_bm25_schema()
            logging.info(f"集合 {self.collection_name} 已存在")
            return

        self.milvus_client.create_collection(
            collection_name=self.collection_name,
            schema=self._generate_schema(),
            index_params=self._generate_index_params()
        )
        self._schema_checked = True
        logging.info(f"集合 {self.collection_name} 创建成功")

    def _check_bm25_schema(self):
        """旧集合没有 BM25 function 时给出明确提示，避免继续用旧的 sparse 结构。"""
        if self._schema_checked:
            return
        desc = self.milvus_client.describe_collection(self.collection_name)
        if not desc.get("functions"):
            raise RuntimeError(
                f"集合 {self.collection_name} 缺少 BM25 function，属于旧的 dense+sparse 结构。"
                "请先删除该集合并重新索引数据（drop_collection 后重跑索引任务）。"
            )
        self._schema_checked = True

    def _generate_schema(self):
        # 生成集合结构，并且启动自动字段
        schema = self.milvus_client.create_schema(auto_id=True, enable_dynamic_field=True)

        for field in self.fields:
            field_name = field["name"]
            field_type = DataType[field["type"]]
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
                # 添加字符串字段；BM25 需要 enable_analyzer + analyzer_params
                analyzer_kwargs = {}
                if field.get("enable_analyzer"):
                    analyzer_kwargs["enable_analyzer"] = True
                if field.get("analyzer_params"):
                    analyzer_kwargs["analyzer_params"] = field["analyzer_params"]
                schema.add_field(
                    field_name=field_name,
                    datatype=field_type,
                    max_length=field.get("max_length", 65535),
                    is_primary=field.get("is_primary", False),
                    **analyzer_kwargs
                )
            else:
                # 添加其他类型字段
                schema.add_field(
                    field_name=field_name,
                    datatype=field_type,
                    is_primary=field.get("is_primary", False)
                )

        # text -> sparse_vector 的稀疏向量由 Milvus 在写入时自动生成
        field_names = {field["name"] for field in self.fields}
        if {"text", "sparse_vector"} <= field_names:
            schema.add_function(
                Function(
                    name="text_bm25",
                    function_type=FunctionType.BM25,
                    input_field_names=["text"],
                    output_field_names=["sparse_vector"],
                )
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
        # 创建集合，保证集合存在才可以插入
        self.create_collection()

        return self.milvus_client.insert(
            collection_name=self.collection_name,
            data=data
        )

    def retrieval(
            self,
            dense_anns_field: str,
            sparse_anns_field: str,
            user_id: int,
            repo_id: int,
            dense_vector: List[float] | None = None,
            sparse_query: str | None = None,
            top_k: int = 10,
            ranker_k: int = 60,
            output_fields: List[str] | None = None,
            filter_expr: str | None = None
    ) -> List[List[Dict]]:
        """
        检索, 过滤设置为当前用户当前仓库
        :param repo_id: 仓库ID
        :param user_id: 用户ID
        :param dense_anns_field: 稠密向量字段名
        :param sparse_anns_field: 稀疏向量字段名
        :param dense_vector: 稠密向量
        :param sparse_query: 稀疏检索的查询文本（Milvus BM25 Function 会据此生成稀疏向量）
        :param top_k: 返回结果数量
        :param ranker_k: RRF 排序参数
        :param output_fields: 输出字段
        :param filter_expr: 额外的过滤表达式
        :return: 检索结果
        """
        output_fields = output_fields or ["text"]

        # 检索同样要确认集合已升级到 BM25 结构，否则会在服务端报模糊错误
        if not self._schema_checked and self.milvus_client.has_collection(self.collection_name):
            self._check_bm25_schema()

        base_filter = f"user_id == {user_id} and repository_id == {repo_id}"
        if filter_expr:
            base_filter = f"({base_filter}) and ({filter_expr})"

        if dense_vector is not None and sparse_query is not None:
            """混合检索：稠密向量 + BM25 稀疏向量"""
            dense_req = AnnSearchRequest(
                data=[dense_vector],
                anns_field=dense_anns_field,
                param={"metric_type": "L2"},
                expr=base_filter,
                limit=top_k,
            )
            sparse_req = AnnSearchRequest(
                data=[sparse_query],
                anns_field=sparse_anns_field,
                param={"metric_type": "BM25"},
                expr=base_filter,
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
                param={"metric_type": "L2"},
                filter=base_filter,
                limit=top_k,
                rerank=RRFRanker(k=ranker_k),
                output_fields=output_fields
            )
        elif sparse_query is not None:
            """仅 BM25 稀疏检索"""
            return self.milvus_client.search(
                collection_name=self.collection_name,
                data=[sparse_query],
                anns_field=sparse_anns_field,
                param={"metric_type": "BM25"},
                rerank=RRFRanker(k=ranker_k),
                filter=base_filter,
                limit=top_k,
                output_fields=output_fields
            )
        else:
            raise ValueError("至少需要提供一个向量参数")

    def query_retrieval(
            self,
            user_id: int,
            repo_id: int,
            output_fields: List[str] | None = None,
            filter_expr: str | None = None,
            limit: int = 100,
            **kwargs
    ) -> List[Dict]:
        """
        标量检索
        :param limit: 查询结果数量
        :param user_id: 用户ID
        :param repo_id: 仓库ID
        :param output_fields: 输出字段
        :param filter_expr: 过滤表达式
        :return:
        """
        base_filter = f"user_id == {user_id} and repository_id == {repo_id}"
        if filter_expr:
            base_filter = f"({base_filter}) and ({filter_expr})"

        return self.milvus_client.query(
            collection_name=self.collection_name,
            filter=base_filter,
            output_fields=output_fields,
            limit=limit,
            **kwargs
        )

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


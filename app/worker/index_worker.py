"""
消费 MQ 做 AST 切分 + 向量入库
"""

import asyncio
import json
import logging
import os

import yaml
from dependency_injector.wiring import inject, Provide
from langchain_core.documents import Document
from tree_sitter import Node

from config.Container import Container
from core.code_parser import CodeParser
from client.mq_consumer import start_consume_mq_message
from core.document_classifier import DocumentClassifier
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel
from schema.domain import MilvusPO
from schema.metadata import Metadata
from schema.request import Request
from core.split_documents import markdown_splitter, docx_splitter, pdf_splitter

@inject
async def handle_message_code(
        request: Request,
        milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
        embedding_model: EmbeddingModel = Provide[Container.embeddingModel],
    ):
    """
    处理 MQ 消息
    :param request: 请求对象
    :param embedding_model: 嵌入模型
    :param milvus_repo: MilvusRepo
    :return: 处理结果
    """

    # # 解析请求
    # request = _message_parser(body)
    # print("request:", request)

    if os.path.exists(request.file_path):
        # 如果传入的文件路径存在, AST切分
        # 创建解析器
        parser = CodeParser()
        # 解析文件, 获取AST的根节点
        root = parser.parse_file(request.file_path)
        # 检测文件语言
        language = parser.detect_language(request.file_path)
        print(f"文件 {request.file_path} 的语言是 {language}")

        if root is None:
            raise ValueError("文件解析失败")

        if language is None:
            raise ValueError("不支持的文件类型")

        # 获取节点集合
        nodes = parser.find_semantic_nodes(root=root, language=language)
        # AST切分
        code_chunks = parser.ast_split(nodes=nodes)
        # 获取稠密向量和稀疏向量
        vector = embedding_model.to_vector([Document(page_content=chunk) for chunk in code_chunks])

        datas = []
        chunk_index = 0 # 当前块索引

        for (dense_vecs, sparse_vecs), code_chunk, node in zip(vector, code_chunks, nodes):
            milvus_po = MilvusPO(
                id=None,
                user_id=request.user_id,
                repository_id=request.repository_id,
                file_path=request.file_path,
                commit_sha=request.commit_sha,
                branch_name=request.branch_name,
                dense_vector=dense_vecs.tolist(),
                sparse_vector=sparse_vecs,
                language=language,
                text=code_chunk,
                metadata=_generate_metadata(
                    request,
                    node,
                    chunk_index
                )
            )

            chunk_index += 1
            datas.append(milvus_po.to_milvus_dict())

        # 向量入库
        milvus_repo.insert(datas)
    else:
        raise FileNotFoundError("文件不存在")

@inject
async def handle_message_markdown_pdf_word(
        request: Request,
        splitter_function: callable,
        milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
        embedding_model: EmbeddingModel = Provide[Container.embeddingModel],
):
    if os.path.exists(request.file_path):
        documents = await splitter_function(request.file_path)
        if not documents:
            logging.warning(f"文件 {request.file_path} 切分后为空，跳过向量化")
            return
        vector = embedding_model.to_vector(documents)

        chunk_index = 0

        datas = []
        for document, (dense_vec, sparse_vec) in zip(documents, vector):
            milvus_po = MilvusPO(
                id=None,
                user_id=request.user_id,
                repository_id=request.repository_id,
                file_path=request.file_path,
                commit_sha=request.commit_sha,
                branch_name=request.branch_name,
                dense_vector=dense_vec.tolist(),
                sparse_vector=sparse_vec,
                language=None,
                text=document.page_content,
                metadata=None # 文档不需要元数据
            )

            chunk_index += 1
            datas.append(milvus_po.to_milvus_dict())
        milvus_repo.insert(datas)
        print(f"文件 {request.file_path} 向量化完成")

    else:
        raise FileNotFoundError("文件不存在")

async def handle_message(body: str):
    # 解析请求
    request = _message_parser(body)
    print("request:", request)
    documentType = DocumentClassifier.classifier(request.file_path)
    print("文件类型:", documentType)

    if "code" in documentType:
        await handle_message_code(request)
    elif "markdown" in documentType:
        await handle_message_markdown_pdf_word(request, markdown_splitter)
    elif "pdf" in documentType:
        await handle_message_markdown_pdf_word(request, pdf_splitter)
    elif "msword" in documentType or "wordprocessingml" in documentType:
        await handle_message_markdown_pdf_word(request, docx_splitter)
    else:
        raise ValueError("不支持的文件类型")


async def code_parser_mq_consumer():
    # 启动 MQ 消费者
    await start_consume_mq_message(callback=handle_message)

def _message_parser(body: str):
    print("处理消息:", body)
    data = json.loads(body)
    # 解析请求
    return Request.from_request(data)


def _generate_metadata(request: Request, node: Node, chunk_index: int) -> Metadata:
    """
    生成元数据
    :param request: 请求对象
    :param node: 节点
    :param chunk_index: 块索引
    :return: 元数据
    """

    return Metadata(
        method_name=node.child_by_field_name("name").text.decode('utf-8') if node.child_by_field_name("name") else None,
        start_line=node.start_point[0] + 1, # (rew, col) 0 ~ based
        end_line=node.end_point[0] + 1,
        chunk_index=chunk_index,
        author=request.author,
        commit_time=request.commit_time,
    )


if __name__ == '__main__':
    container = Container()
    with open(r"D:\Job_project\gitlet_ai_helper\config.yaml", encoding="utf-8") as f:
        config_data = yaml.safe_load(f)
    container.config.from_dict(config_data["config"])
    container.wire(modules=[__name__])
    asyncio.run(code_parser_mq_consumer())
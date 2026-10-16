"""
消费 MQ 做 AST 切分 + 向量入库
"""

import asyncio
import json
import logging
import os
from asyncio import Semaphore

import yaml
from aiohttp.web_fileresponse import content_type
from dependency_injector.wiring import inject, Provide
from langchain_core.documents import Document
from redis import Redis
from tree_sitter import Node

from jinja2 import Template
from config.Container import Container
from core.code_parser import CodeParser
from client.mq_consumer import start_consume_mq_message
from core.document_classifier import DocumentClassifier
from core.redis_key import path_to_redis_key
from repository.milvus_repo import MilvusRepo
from client.embeding import EmbeddingModel
from schema.domain import MilvusPO, RequestDTO
from schema.base_model import Metadata
from schema.request import Request
from core.split_documents import markdown_splitter, docx_splitter, pdf_splitter

@inject
async def handle_message_code(
        request: RequestDTO,
        milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
        embedding_model: EmbeddingModel = Provide[Container.embedding_model],
        commit_message_template: Template = Provide[Container.commit_message_template],
    ):
    """
    处理 MQ 消息
    :param request: 请求对象
    :param embedding_model: 嵌入模型
    :param milvus_repo: MilvusRepo
    :param commit_message_template: 提交信息模板
    :return: 处理结果
    """

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

        # 遍历代码块
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
                ),
                content_type="code"
            )

            chunk_index += 1
            datas.append(milvus_po.to_milvus_dict())

        # 填充槽位
        to_template = {
            "merge_files" : list(dict.fromkeys(request.merge_files or [])),
            "repository_id" : request.repository_id,
            "commit_sha" : request.commit_sha,
            "branch_name" : request.branch_name,
            "user_id" : request.user_id,
            "file_path" : request.file_path,
            "message" : request.message,
            "author" : request.author,
            "commit_time" : request.commit_time,
            "status" : request.status,
            "pr_name" : request.pr_name,
        }

        # 提交信息创建向量
        commit_message = commit_message_template.render(to_template)
        vector = embedding_model.to_vector([Document(page_content=commit_message)])

        for (dense_vecs, sparse_vecs) in vector:
            milvus_po = MilvusPO(
                id=None,
                user_id=request.user_id,
                repository_id=request.repository_id,
                file_path=request.file_path,
                commit_sha=request.commit_sha,
                branch_name=request.branch_name,
                dense_vector=dense_vecs.tolist(),
                sparse_vector=sparse_vecs,
                language=None,
                text=commit_message,
                metadata=None,
                content_type="commit_message"
            )
            datas.append(milvus_po.to_milvus_dict())

        print(f"提交信息 {commit_message} 向量化完成")

        # 向量入库
        milvus_repo.insert(datas)
    else:
        raise FileNotFoundError("文件不存在")

@inject
async def handle_message_markdown_pdf_word(
        request: RequestDTO,
        splitter_function: callable,
        milvus_repo: MilvusRepo = Provide[Container.milvus_repo],
        embedding_model: EmbeddingModel = Provide[Container.embedding_model],
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
                metadata=None, # 文档不需要元数据
                content_type=document.metadata.content_type
            )

            chunk_index += 1
            datas.append(milvus_po.to_milvus_dict())
        milvus_repo.insert(datas)
        print(f"文件 {request.file_path} 向量化完成")

    else:
        raise FileNotFoundError("文件不存在")

async def handle_message(body: str, redis_client: Redis = Provide[Container.redis_client]):
    # 解析请求
    request = _message_parser(body)
    print("request:", request)

    async def file_handler(request_dto: RequestDTO):
        documentType = DocumentClassifier.classifier(request_dto.file_path)
        print("文件类型:", documentType)

        if "code" in documentType:
            await handle_message_code(request_dto)
        elif "markdown" in documentType:
            await handle_message_markdown_pdf_word(request_dto, markdown_splitter)
        elif "pdf" in documentType:
            await handle_message_markdown_pdf_word(request_dto, pdf_splitter)
        elif "msword" in documentType or "wordprocessingml" in documentType:
            await handle_message_markdown_pdf_word(request_dto, docx_splitter)
        else:
            raise ValueError("不支持的文件类型")

        # 处理后的文件存入Redis防止重复处理，如果说文件的SHA值也就是路径相同就跳过，用于去重和追溯
        key = path_to_redis_key(request_dto.file_path)
        redis_client.set(key, request_dto.file_path)

    async def bounded_file_handler(req: RequestDTO, semaphore: Semaphore):
        async with semaphore:
            return await file_handler(req)

    tasks = []
    # 遍历提交记录，处理文件
    for commit in request.pr_info.commits:
        for file in commit.tracked_files:
            # Redis判断文件是否已处理
            key = path_to_redis_key(file.file_path)
            if redis_client.exists(key):
                print(f"文件 {file.file_path} 已处理过，跳过")
                continue

            req = RequestDTO.from_dict({
                "user_id": request.user_id,
                "repository_id": request.repository_id,
                "branch_name": request.branch_name,
                "commit_sha": commit.commit_sha,
                "message": commit.message,
                "merge_files": commit.merge_files,
                "author": request.pr_info.author,
                "commit_time": commit.time,
                "file_path": file.file_path,
                "status": file.status,
                "pr_name": request.pr_info.pr_name,
            })
            tasks.append(bounded_file_handler(req, asyncio.Semaphore(10)))

    res = await asyncio.gather(*tasks, return_exceptions=True)

    # 遍历结果日志
    for idx, result in enumerate(res):
        if isinstance(result, Exception):
            print(f"任务{idx} 异常：{result}")
        else:
            print(f"任务{idx} 处理完成")



async def code_parser_mq_consumer():
    # 启动 MQ 消费者
    await start_consume_mq_message(callback=handle_message)

def _message_parser(body: str):
    print("处理消息:", body)
    data = json.loads(body)
    # 解析请求
    return Request.from_request(data)


def _generate_metadata(request: RequestDTO, node: Node, chunk_index: int) -> Metadata:
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
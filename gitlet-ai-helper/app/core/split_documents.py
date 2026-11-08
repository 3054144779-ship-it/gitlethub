"""
文档分割
"""
import asyncio
from typing import List

from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.document_loaders import (
    UnstructuredMarkdownLoader, UnstructuredWordDocumentLoader, PyMuPDFLoader)

"""
Markdown 文档分割
"""
async def markdown_splitter(file_path: str) -> List[Document]:
    markdown_loader = UnstructuredMarkdownLoader(file_path=file_path, mode="elements")
    markdown = await markdown_loader.aload()
    markdown_optimization = await asyncio.to_thread(_structural_optimization, markdown)
    documents = await asyncio.to_thread(_sync_split, markdown_optimization)
    return documents

"""
Docx 文档分割
"""
async def docx_splitter(file_path: str) -> List[Document]:
    docx_loader = UnstructuredWordDocumentLoader(
        file_path=file_path,
        mode="elements",
        chunking_strategy="by_title", # 按标题分块
        max_characters=1000, # 最大字符数
        combine_text_under_n_chars=500, # 结合文本下的字符数
    )

    docx = await docx_loader.aload()
    documents = await asyncio.to_thread(_sync_split, docx)
    return documents


"""
PDF 文档分割(后续可根据需求切换到 MinerU)
"""
async def pdf_splitter(file_path: str) -> List[Document]:
    loader = PyMuPDFLoader(file_path=file_path)
    pdf = await loader.aload()
    documents = await asyncio.to_thread(_sync_split, pdf)
    return documents

# 文档分割
def _sync_split(documents: List[Document]):
    return RecursiveCharacterTextSplitter(
        separators=["\n\n", "\n", " ", ""],
        chunk_size=1000,
        chunk_overlap=100,
        length_function=len,
        add_start_index=True,
    ).split_documents(documents=documents)


# 标题层级结构优化（Markdown / PDF 通用）
def _structural_optimization(documents: List[Document]) -> List[Document]:
    stack = []
    optimized_documents = []
    for document in documents:
        category = document.metadata.get('category', '')
        if category == "Title":
            current_category_level = document.metadata.get('category_depth', 0)
            while stack and current_category_level <= stack[-1][0]:
                stack.pop()
            stack.append((current_category_level, document.page_content))
        else:
            page_content = ">".join([doc for _, doc in stack]) + "\n" + document.page_content
            optimized_documents.append(Document(
                page_content=page_content,
                metadata=document.metadata
            ))
    if not optimized_documents and documents:
        return documents
    return optimized_documents

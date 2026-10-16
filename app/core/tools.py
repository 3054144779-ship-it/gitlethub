import os

from langchain_core.tools import tool

@tool(description="读取文件内容")
async def read_file(file_path: str):
    """
    读取文件内容
    :param file_path: 文件路径
    :return: 文件内容
    """
    # 规范化路径
    normpath = os.path.normpath(file_path)

    if not os.path.exists(normpath):
        return f"文件 {file_path} 不存在"

    with open(normpath, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


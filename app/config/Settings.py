"""
配置模块
"""
import os
from logging import info

from dotenv import load_dotenv

# 在配置模块顶部统一加载一次
load_dotenv()

class Settings:
    MESSAGE_QUEUE_URL = os.getenv("MESSAGE_QUEUE_URL")
    PR_MERGED_QUEUE = os.getenv("PR_MERGED_QUEUE")
    MILVUS_URL = os.getenv("MILVUS_URL")

if __name__ == '__main__':
    settings = Settings()
    info(settings)
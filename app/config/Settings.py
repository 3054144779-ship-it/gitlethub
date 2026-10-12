"""
配置模块
"""
import os
import logging
from logging import info

from dotenv import load_dotenv

# 在配置模块顶部统一加载一次
load_dotenv()

logging.basicConfig(
    level=logging.INFO,  # 重点：>=INFO 的日志才会输出
    format="%(asctime)s - %(levelname)s - %(message)s"
)

# 配置：设置级别为 INFO，输出到控制台
class Settings:
    MESSAGE_QUEUE_URL = os.getenv("MESSAGE_QUEUE_URL")
    PR_MERGED_QUEUE = os.getenv("PR_MERGED_QUEUE")

if __name__ == '__main__':
    settings = Settings()
    info(settings)
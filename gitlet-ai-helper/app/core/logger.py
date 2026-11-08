"""
日志配置

"""
import logging

# 配置：设置级别为 INFO，输出到控制台
logging.basicConfig(
    level=logging.INFO,  # 重点：>=INFO 的日志才会输出
    format="%(asctime)s - %(levelname)s - %(message)s"
)

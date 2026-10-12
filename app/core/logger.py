import logging

logging.basicConfig(
    level=logging.INFO,  # 重点：>=INFO 的日志才会输出
    format="%(asctime)s - %(levelname)s - %(message)s"
)

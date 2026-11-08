import hmac
import hashlib
import os

from config.Settings import Settings
# 从环境变量中获取密钥
# SECRET = os.environ["REDIS_KEY_SECRET"].encode()
SECRET = bytes.fromhex(Settings.REDIS_KEY_SECRET)

def _hmac_hex(value: str) -> str:
    return hmac.new(
        SECRET,
        value.encode("utf-8"),
        hashlib.sha256
    ).hexdigest()


def path_to_redis_key(path: str, prefix: str = "path") -> str:
    # 路径规范化，避免 /a/b 和 /a/b/ 被当成不同 key
    normalized = os.path.normpath(path)
    return f"{prefix}:{_hmac_hex(normalized)}"


def prompt_to_redis_key(prompt: str, prefix: str = "prompt") -> str:
    # 默认不改写 prompt，保证语义一致
    return f"{prefix}:{_hmac_hex(prompt)}"



if __name__ == '__main__':
    print(path_to_redis_key("/data/user/123/file.txt"))
    print(prompt_to_redis_key("请帮我总结这段文字，要求保留关键信息..."))

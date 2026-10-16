import hmac
import hashlib
import os

from config.Settings import Settings
# 从环境变量中获取密钥
# SECRET = os.environ["REDIS_KEY_SECRET"].encode()
SECRET = bytes.fromhex(Settings.REDIS_KEY_SECRET)

def path_to_redis_key(path: str, prefix: str = "path") -> str:
    # 规范化路径，避免 /a/b 和 /a/b/ 被当成不同 key
    normalized = os.path.normpath(path)
    mac = hmac.new(
        SECRET,
        normalized.encode("utf-8"),
        hashlib.sha256
    ).hexdigest()
    return f"{prefix}:{mac}"

if __name__ == '__main__':
    key = path_to_redis_key("/data/user/123/file.txt")
    print(key)

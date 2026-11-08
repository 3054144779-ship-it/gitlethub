"""
响应体，返回JSON格式
"""
from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel

class Response(BaseModel):
    code: int
    message: str
    data: Any

    @classmethod
    def ok(cls, data: Any = None):
        return cls(
            code=0,
            message="ok",
            data=data
        )

    @classmethod
    def fail(cls, code: int, message: str):
        return cls(
            code=code,
            message=message,
            data=None
        )

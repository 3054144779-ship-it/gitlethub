"""
响应体，返回JSON格式
"""
from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel

@dataclass
class Response(BaseModel):
    code: str
    message: str
    data: Any

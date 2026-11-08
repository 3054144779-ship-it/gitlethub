from typing import List, Tuple

from pydantic import BaseModel

class EmbedRequest(BaseModel):
    texts: list[str]

class RerankRequest(BaseModel):
    pairs: List[Tuple[str, str]]
    top_k: int = 10
    text_field: str = "text"
    batch_size: int = 32
    normalize: bool = True

"""
文件分类器，用于将文件分类
"""

import filetype
from pathlib import Path

class DocumentClassifier:
    """
    文件分类器，用于将文件分类
    """
    @classmethod
    def classifier(cls, file_path: str):
        kind = filetype.guess(file_path)
        if kind is None:
            # 走二次判断
            return cls._guess_by_magic(file_path)
        return kind.mime

    @classmethod
    def _guess_by_magic(cls, file_path: str):
        """
        二次判断，用于判断filetype库判断失败的情况
        """
        p = Path(file_path)

        with open(file_path, "rb") as f:
            head = f.read(4096)

        if head.startswith(b"\x89PNG\r\n\x1a\n"):
            return "png"
        if head.startswith(b"\xff\xd8\xff"):
            return "jpg"
        if head.startswith(b"%PDF-"):
            return "pdf"
        if head.startswith(b"PK\x03\x04"):
            return "zip"
        if head.startswith(b"\x1f\x8b"):
            return "gzip"

        if p.suffix.lower() in {".md", ".markdown", ".mdown", ".mkd"}:
            return "markdown"

        return "code"

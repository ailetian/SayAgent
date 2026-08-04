"""
文档加载器路由
按文件后缀自动选择对应的 Loader
"""

from pathlib import Path
from langchain_community.document_loaders import TextLoader, PyPDFLoader, CSVLoader
from langchain_community.document_loaders import Docx2txtLoader, UnstructuredExcelLoader

LOADER_MAP = {
    ".md":   lambda p: TextLoader(str(p), encoding="utf-8").load(),
    ".txt":  lambda p: TextLoader(str(p), encoding="utf-8").load(),
    ".pdf":  lambda p: PyPDFLoader(str(p)).load(),
    ".docx": lambda p: Docx2txtLoader(str(p)).load(),
    ".xlsx": lambda p: UnstructuredExcelLoader(str(p), mode="elements").load(),
    ".csv":  lambda p: CSVLoader(str(p), encoding="utf-8").load(),
}


def load_document(file_path: str | Path) -> list:
    file_path = Path(file_path)
    ext = file_path.suffix.lower()

    loader = LOADER_MAP.get(ext)
    if loader is None:
        raise ValueError(f"不支持的文档格式: {ext}，支持: {list(LOADER_MAP.keys())}")

    docs = loader(file_path)

    for doc in docs:
        doc.metadata.setdefault("source", file_path.name)
        doc.metadata.setdefault("format", ext.lstrip("."))
        doc.metadata.setdefault("file_path", str(file_path))

    return docs

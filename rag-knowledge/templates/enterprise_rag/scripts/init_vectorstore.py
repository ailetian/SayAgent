"""
向量库初始化脚本
遍历 data/ 目录下所有文档 → 加载 → 切块 → 写入 Chroma

用法: python scripts/init_vectorstore.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
sys.stdout.reconfigure(encoding='utf-8')

from langchain_chroma import Chroma
from langchain_huggingface import HuggingFaceEmbeddings
from config.settings import settings
from core.loaders.router import load_document
from core.splitters.router import split_document


def init():
    data_dir = Path(__file__).parent.parent / "data"
    if not data_dir.exists():
        print(f"数据目录不存在: {data_dir}")
        print("请创建 data/ 目录并放入文档文件。")
        return

    embed_model = HuggingFaceEmbeddings(
        model_name=settings.embed_model_path,
        model_kwargs={"device": settings.embed_device},
    )

    all_chunks = []
    supported = {".md", ".txt", ".pdf", ".docx", ".xlsx", ".csv"}

    for file_path in data_dir.iterdir():
        if file_path.suffix.lower() not in supported:
            print(f"  跳过不支持格式: {file_path.name}")
            continue

        print(f"  加载: {file_path.name}")
        docs = load_document(file_path)
        chunks = split_document(
            docs,
            doc_format=file_path.suffix.lstrip("."),
            chunk_size=settings.chunk_size,
            chunk_overlap=settings.chunk_overlap,
        )
        all_chunks.extend(chunks)
        print(f"    → {len(chunks)} 个文档块")

    if not all_chunks:
        print("没有找到可处理的文档。")
        return

    print(f"\n写入向量库...")
    Chroma.from_documents(
        documents=all_chunks,
        embedding=embed_model,
        collection_name=settings.chroma_collection_name,
        persist_directory=settings.chroma_persist_dir,
    )
    print(f"完成！共 {len(all_chunks)} 个文档块。")


if __name__ == "__main__":
    init()

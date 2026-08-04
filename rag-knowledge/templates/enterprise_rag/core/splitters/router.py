"""
切块策略路由
按文档格式和内容特征选择切块方式
"""

from langchain_core.documents import Document
from langchain_text_splitters import MarkdownHeaderTextSplitter, RecursiveCharacterTextSplitter


def split_by_headers(text: str, headers: list[tuple], chunk_size: int = 800, chunk_overlap: int = 120) -> list[Document]:
    """Markdown/docs 标题切分：先按标题拆，长段落再二次拆分"""
    splitter = MarkdownHeaderTextSplitter(
        headers_to_split_on=headers,
        strip_headers=False,
    )
    chunks = splitter.split_text(text)

    char_splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size, chunk_overlap=chunk_overlap,
        separators=["\n\n", "\n", "。", "；", "，", " ", ""],
    )
    result = []
    for c in chunks:
        if len(c.page_content) > chunk_size * 0.6:
            result.extend(char_splitter.split_documents([c]))
        else:
            result.append(c)
    return result


def split_by_paragraph(text: str, chunk_size: int = 1000, chunk_overlap: int = 150) -> list[Document]:
    """纯文本按段落切分"""
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size, chunk_overlap=chunk_overlap,
        separators=["\n\n", "\n", "。", "；", "，", " ", ""],
    )
    return splitter.create_documents([text])


def split_by_row(rows: list[dict], text_template: str = "{col}: {val}") -> list[Document]:
    """Excel/CSV 每行转自然语言描述"""
    docs = []
    for i, row in enumerate(rows):
        parts = []
        for col, val in row.items():
            parts.append(text_template.format(col=col, val=val))
        content = "；".join(parts)
        docs.append(Document(page_content=content, metadata={"row_index": i}))
    return docs


def split_document(docs: list[Document], doc_format: str, chunk_size: int = 800, chunk_overlap: int = 120) -> list[Document]:
    """统一入口：按格式路由切块策略"""
    if doc_format in ("md", "docx"):
        headers = [("##", "章节"), ("###", "小节")]
        result = []
        for doc in docs:
            result.extend(split_by_headers(doc.page_content, headers, chunk_size, chunk_overlap))
        return result
    elif doc_format in ("xlsx", "csv"):
        # 表格实际内容在 metadata 或 page_content 中
        return docs  # 表格通常已由 loader 按行处理
    else:
        result = []
        for doc in docs:
            result.extend(split_by_paragraph(doc.page_content, chunk_size, chunk_overlap))
        return result

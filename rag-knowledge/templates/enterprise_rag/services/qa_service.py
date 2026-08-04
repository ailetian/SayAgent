"""
问答服务 — 业务编排层

完整链路：
  用户问题
    → [步骤1] Query Rewriting（口语→书面语 + 指代消解）
    → [步骤2] 混合检索（BM25 + 向量 + RRF融合）
    → [步骤3] 兜底判断（相似度 < 阈值 → 拒答）
    → [步骤4] Prompt 拼接 + LLM 生成
    → [步骤5] 组装 SourceInfo → 返回
"""

import time
import sys
sys.stdout.reconfigure(encoding='utf-8')

from langchain_deepseek import ChatDeepSeek
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_chroma import Chroma
from langchain_community.retrievers import BM25Retriever
from langchain_core.documents import Document
import jieba

from config.settings import settings
from models.qa import QuestionRequest, AnswerResponse, SourceInfo


# ---- 模块级初始化（只加载一次） ----
llm = ChatDeepSeek(
    model=settings.llm_model,
    api_key=settings.llm_api_key,
    temperature=settings.llm_temperature,
)

embed_model = HuggingFaceEmbeddings(
    model_name=settings.embed_model_path,
    model_kwargs={"device": settings.embed_device},
)

vectorstore = Chroma(
    embedding_function=embed_model,
    collection_name=settings.chroma_collection_name,
    persist_directory=settings.chroma_persist_dir,
)

# ---- BM25 构建（中文分词） ----
def _build_bm25():
    raw = vectorstore.get()
    docs = [
        Document(page_content=text, metadata=meta or {})
        for text, meta in zip(raw["documents"], raw["metadatas"])
    ]
    return BM25Retriever.from_documents(
        docs,
        k=settings.retrieval_top_k,
        preprocess_func=lambda t: list(jieba.cut(t)),
    )

bm25_retriever = _build_bm25()


# ---- [步骤1] Query Rewriting ----
def rewrite_query(question: str, history: list[str] | None = None) -> str:
    if not settings.rewrite_enabled:
        return question

    history_str = ""
    if history:
        history_str = "对话历史：\n" + "\n".join(history[-6:]) + "\n\n"

    prompt = f"""你是一个搜索查询优化器。把用户口语改写成 1~3 个适合检索文档的书面查询。
每行一个，不要编号和解释。

{history_str}用户问题：{question}
检索查询："""

    try:
        response = llm.invoke(prompt)
        queries = [q.strip() for q in response.content.strip().split("\n") if q.strip()]
        return " ".join(queries)
    except Exception:
        return question


# ---- [步骤2] 混合检索（RRF融合） ----
def hybrid_search(query: str) -> list[tuple]:
    bm25_results = bm25_retriever.invoke(query)
    vec_raw = vectorstore.similarity_search_with_relevance_scores(
        query, k=settings.retrieval_top_k
    )

    rrf_scores = {}
    label_map = {}

    for rank, doc in enumerate(bm25_results, 1):
        key = doc.page_content[:100]
        rrf_scores[key] = rrf_scores.get(key, 0) + 1.0 / (settings.rrf_k + rank)
        label_map[key] = (doc, "BM25")

    for rank, (doc, _score) in enumerate(vec_raw, 1):
        key = doc.page_content[:100]
        old = rrf_scores.get(key, 0)
        rrf_scores[key] = old + 1.0 / (settings.rrf_k + rank)
        label_map[key] = (doc, "Both" if old > 0 else "Vector")

    sorted_items = sorted(rrf_scores.items(), key=lambda x: x[1], reverse=True)
    return [
        (*label_map[key], round(score, 4))
        for key, score in sorted_items[:settings.retrieval_top_k]
    ]


# ---- [步骤3] 兜底判断 ----
def check_threshold(results: list[tuple]) -> list[tuple]:
    return [
        (doc, label, score)
        for doc, label, score in results
        if score >= settings.similarity_threshold
    ]


# ---- [步骤4+5] 生成 + 组装 ----
def ask(req: QuestionRequest, session_history: list[str] | None = None) -> AnswerResponse:
    start = time.time()

    # [步骤1] Query Rewriting
    search_query = rewrite_query(req.question, session_history)

    # [步骤2] 混合检索
    try:
        results = hybrid_search(search_query)
    except Exception as e:
        return AnswerResponse(
            answer="检索服务暂时不可用，请稍后重试。",
            sources=[],
            fallback_triggered=True,
            latency_ms=(time.time() - start) * 1000,
        )

    # [步骤3] 兜底判断
    filtered = check_threshold(results)
    if not filtered:
        return AnswerResponse(
            answer="抱歉，基于当前知识库无法回答该问题。请尝试换一种问法。",
            sources=[],
            fallback_triggered=True,
            latency_ms=(time.time() - start) * 1000,
        )

    # [步骤4] 拼接 prompt
    context_parts = []
    for i, (doc, label, score) in enumerate(filtered[:settings.retrieval_final_k], 1):
        chapter = doc.metadata.get("章节", "")
        section = doc.metadata.get("小节", "")
        context_parts.append(f"[来源{i}] {chapter} > {section} | {label}\n{doc.page_content}")

    prompt = f"""你是公司制度问答助手。严格根据以下文档片段回答用户问题。不编造文档中没有的信息。

文档片段：
{"\n\n".join(context_parts)}

用户问题：{req.question}
回答："""

    try:
        answer = llm.invoke(prompt).content
    except Exception as e:
        return AnswerResponse(
            answer=f"生成回答时出错，请稍后重试。",
            sources=[],
            fallback_triggered=True,
            latency_ms=(time.time() - start) * 1000,
        )

    # [步骤5] 组装来源
    sources = [
        SourceInfo(
            chapter=doc.metadata.get("章节", ""),
            section=doc.metadata.get("小节", ""),
            snippet=doc.page_content[:200],
            score=score,
        )
        for doc, _, score in filtered[:settings.retrieval_final_k]
    ]

    return AnswerResponse(
        answer=answer,
        sources=sources,
        fallback_triggered=False,
        latency_ms=(time.time() - start) * 1000,
    )

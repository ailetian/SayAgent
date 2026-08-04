"""
RAG 可复用代码片段
==================
每个函数独立可运行，复制到项目中直接使用。
"""

import sys
sys.stdout.reconfigure(encoding='utf-8')


# ============================================================
# 片段1: BM25 + jieba 中文分词检索器
# ============================================================

def build_bm25_retriever(documents: list, top_k: int = 10):
    """
    构建支持中文的 BM25 检索器。
    documents: list[Document] — 从向量库或文件加载的文档列表
    返回: BM25Retriever 实例

    使用方式:
        retriever = build_bm25_retriever(docs)
        results = retriever.invoke("年假有几天")
    """
    import jieba
    from langchain_community.retrievers import BM25Retriever

    def tokenizer(text: str) -> list[str]:
        return list(jieba.cut(text))

    return BM25Retriever.from_documents(
        documents,
        k=top_k,
        preprocess_func=tokenizer,
    )


# ============================================================
# 片段2: RRF 融合
# ============================================================

def rrf_fusion(
    bm25_results: list,
    vector_results: list,
    top_k: int = 10,
    k: int = 60,
):
    """
    RRF 倒数排名融合：不看分数看排名。

    参数:
      bm25_results:   [(Document, score), ...] 或 [Document, ...]
      vector_results: [(Document, score), ...] 或 [Document, ...]
      top_k: 返回前几条
      k: RRF 平滑因子（默认60，小数据集可调为30，大数据集可调为100）

    返回:
      [(Document, 来源标签, RRF分数), ...]
      来源标签: "两者命中" / "BM25(关键词)" / "向量(语义)"
    """
    rrf_scores = {}
    label_map = {}

    # BM25 贡献
    for rank, item in enumerate(bm25_results, 1):
        doc = item[0] if isinstance(item, tuple) else item
        key = doc.page_content[:100]
        added = 1.0 / (k + rank)
        rrf_scores[key] = rrf_scores.get(key, 0) + added
        label_map[key] = (doc, "BM25(关键词)")

    # 向量贡献
    for rank, item in enumerate(vector_results, 1):
        doc = item[0] if isinstance(item, tuple) else item
        key = doc.page_content[:100]
        added = 1.0 / (k + rank)
        old = rrf_scores.get(key, 0)
        rrf_scores[key] = old + added
        label_map[key] = (doc, "两者命中" if old > 0 else "向量(语义)")

    sorted_items = sorted(rrf_scores.items(), key=lambda x: x[1], reverse=True)
    result = []
    for key, rrf_score in sorted_items[:top_k]:
        doc, label = label_map[key]
        result.append((doc, label, round(rrf_score, 4)))

    return result


# ============================================================
# 片段3: Query Rewriting 模板
# ============================================================

QUERY_REWRITE_PROMPT = """你是一个搜索查询优化器。把用户口语改写成 1~3 个适合检索文档的书面查询。
每行一个，不要编号和解释。

{history}
用户问题：{question}
检索查询："""


def rewrite_query(llm, user_question: str, history_texts: list[str] | None = None) -> str:
    """
    口语 → 书面查询改写 + 指代消解。

    llm: LangChain ChatModel 实例
    返回: 改写后的查询字符串（多个查询用空格连接）
    """
    history_str = ""
    if history_texts:
        history_str = "对话历史：\n" + "\n".join(history_texts[-6:]) + "\n\n"

    prompt = QUERY_REWRITE_PROMPT.format(
        history=history_str,
        question=user_question,
    )
    response = llm.invoke(prompt)
    queries = [q.strip() for q in response.content.strip().split("\n") if q.strip()]
    return " ".join(queries)


# ============================================================
# 片段4: 检索降级兜底
# ============================================================

def generate_with_fallback(llm, question: str, retrieved_docs, threshold: float = 0.6):
    """
    检索结果太差时拒答，不硬编。

    retrieved_docs: [(Document, score), ...]
    threshold: 低于此分数的文档被过滤
    """
    filtered = [(doc, score) for doc, score in retrieved_docs if score >= threshold]

    if not filtered:
        return "抱歉，基于当前知识库无法回答该问题。请尝试换一种问法。"

    context_parts = []
    for i, (doc, score) in enumerate(filtered, 1):
        chapter = doc.metadata.get('章节', '')
        section = doc.metadata.get('小节', '')
        context_parts.append(
            f"[来源{i}] {chapter} > {section}\n{doc.page_content}"
        )

    NL = "\n\n"
    prompt = f"""你是知识库问答助手。严格按文档片段回答，不编造文档中没有的信息。

文档片段：
{NL.join(context_parts)}

用户问题：{question}
回答："""

    return llm.invoke(prompt).content


# ============================================================
# 片段5: 评测指标 — 召回率 + MRR
# ============================================================

def calc_recall_at_k(retrieved_docs, keywords: list[str], k: int = 5) -> float:
    """召回率@K：前K个检索结果中命中了多少关键词"""
    hit = set()
    for doc in retrieved_docs[:k]:
        for kw in keywords:
            if kw in doc.page_content:
                hit.add(kw)
    return len(hit) / len(keywords) if keywords else 0


def calc_mrr(retrieved_docs, target_content: str) -> float:
    """MRR：第一个相关文档排在第几位？1/rank"""
    for rank, doc in enumerate(retrieved_docs, 1):
        if target_content in doc.page_content:
            return 1.0 / rank
    return 0.0


# ============================================================
# 片段6: 用 LLM 从文档自动生成 QA 测试对
# ============================================================

def generate_test_cases(llm, document_text: str, num_questions: int = 3) -> list[dict]:
    """
    冷启动：没有历史查询数据时，用 LLM 根据文档内容自动生成测试用例。
    每道题包含：question / answer_hint / 关键词
    """
    prompt = f"""根据以下文档内容，生成 {num_questions} 个用户可能会问的问题。
每个问题标注：问题类型（精确查询/语义查询）和答案中应该包含的关键词。

输出格式（JSON数组）：
[
  {{"question": "...", "type": "精确查询", "keywords": ["...", "..."]}},
  ...
]

文档内容：
{document_text[:3000]}
"""
    import json
    response = llm.invoke(prompt)
    raw = response.content.strip()
    # 处理可能的 markdown 包裹
    raw = raw.replace("```json", "").replace("```", "").strip()
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return []


# ============================================================
# 片段7: 多路召回（Multi-Query Retrieval）
# ============================================================

def generate_query_variants(llm, question: str, n: int = 3) -> list[str]:
    """用 LLM 生成多个查询变体，每个变体用不同表述搜索"""
    prompt = f"""把以下问题改写成 {n} 个不同表述的查询，用于搜索文档库。
每行一个，覆盖不同的关键词和表达方式。

原问题：{question}
查询变体："""
    response = llm.invoke(prompt)
    return [q.strip() for q in response.content.strip().split("\n") if q.strip()]


def merge_multi_query_results(all_result_groups: list, top_k: int = 10, k: int = 60) -> list:
    """
    RRF 合并多组检索结果（每组是不同查询变体的结果）。

    all_result_groups: [[(doc, label, score), ...], ...]
    返回: [(doc, label, rrf_score), ...]
    """
    rrf_scores = {}
    doc_map = {}
    for result_group in all_result_groups:
        for rank, item in enumerate(result_group, 1):
            doc = item[0]
            label = item[1] if len(item) > 1 else ""
            key = doc.page_content[:100]
            rrf_scores[key] = rrf_scores.get(key, 0) + 1.0 / (k + rank)
            doc_map[key] = (doc, label)

    sorted_items = sorted(rrf_scores.items(), key=lambda x: x[1], reverse=True)
    return [
        (*doc_map[key], round(score, 4))
        for key, score in sorted_items[:top_k]
    ]


# ============================================================
# 自测
# ============================================================
if __name__ == "__main__":
    print("rag_snippets.py — 代码片段库")
    print("每个函数可独立调用，复制到项目中直接使用。")
    print()
    print("可用片段：")
    print("  1. build_bm25_retriever  — BM25 + jieba 中文分词")
    print("  2. rrf_fusion             — RRF 倒数排名融合")
    print("  3. rewrite_query          — Query Rewriting + 指代消解")
    print("  4. generate_with_fallback — 检索降级兜底")
    print("  5. calc_recall_at_k       — 召回率@K 计算")
    print("  6. calc_mrr               — MRR 计算")
    print("  7. generate_test_cases    — LLM 自动生成测试用例")

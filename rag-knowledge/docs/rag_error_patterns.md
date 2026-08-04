# RAG 错误模式库·自进化

> 每次在 RAG 项目里踩坑 → 修好 → 追加一行到本文件
> 目标：同一个坑不踩第三次

---

## 快速检索表

| # | 错误关键字 | 根因 | 解决 | 发现日期 |
|---|-----------|------|------|----------|
| E1 | `ModuleNotFoundError: No module named 'langchain.text_splitter'` | langchain 0.3+ 把 text_splitter 拆到了独立包 | 改用 `from langchain_text_splitters import ...` | 2026-06-12 |
| E2 | BM25 对所有查询返回相同结果 | BM25 默认按空格分词，中文无空格→整句当一词 | 加 jieba 分词：`BM25Retriever.from_documents(docs, preprocess_func=lambda t: list(jieba.cut(t)))` | 2026-06-16 |
| E3 | 评测关键词全部匹配失败 | 文档格式和关键词不一致（如文档"5 天" vs 关键词"5天"） | 写测试用例前先读文档原文，对齐格式 | 2026-06-16 |
| E4 | TOP_K 和阈值的关系被误认为"取交集" | TOP_K 是检索数量，阈值是过滤条件，两者独立 | 先取 TOP_K 条 → 再按阈值逐条过滤 → 不过滤全部通过时可能引入噪音 | 2026-06-13 |
| E5 | Chroma 返回的 metadata 为 None | Chroma 存 None 时返回 None，后续 `.get()` 炸 | 构建 Document 时兜底：`metadata=meta or {}` | 2026-06-12 |
| E6 | BGE-M3 加载极慢（首次） | 首次加载需下载模型（~2GB） | 首次初始化耐心等待，后续用本地 snapshot 路径 | 2026-06-12 |
| E7 | `DeprecationWarning: langchain-community is being sunset` | langchain-community 包正在被弃用 | 逐步迁移到独立包（BM25Retriever 暂不受影响） | 2026-06-14 |
| E8 | Query Rewriting 生成的查询与原问题脱节 | LLM 改写时丢失了原问题的约束条件 | prompt 里强调"保留原问题中的所有约束条件，只改写表达方式" | - |
| E9 | Rerank 后结果反而变差 | Rerank 模型和 Embedding 模型的语义空间不一致 | 用同厂商的 Embedding + Rerank（如 BGE-M3 + BGE-Reranker） | - |
| E10 | 向量库中同一文档出现多条重复 | 切块重叠 + 多次导入导致 | 导入前清空集合或用 `upsert` 代替 `add` | - |

> E8~E10 为预测的高频坑，标注 "-" 的待实际踩坑后更新。

---

## 详细排查

### E2: BM25 中文分词（最高频）

**现象：** BM25 检索对所有查询返回相同结果，或结果完全无关。

**步骤：**
1. 确认项目中使用了中文文档
2. 检查 BM25Retriever 是否传了 `preprocess_func`
3. 没有 → 安装 jieba + 添加分词函数

```python
import jieba

def chinese_tokenizer(text: str) -> list[str]:
    return list(jieba.cut(text))

BM25Retriever.from_documents(docs, preprocess_func=chinese_tokenizer)
```

### E3: 评测数据与文档格式不一致

**现象：** 跑 benchmark 得分异常低，检查发现关键词明明在文档里却显示未命中。

**步骤：**
1. 打开源文档，找到对应段落
2. 对比关键词和文档原文的格式（空格、标点、全角半角）
3. 用文档原文的实际格式修正关键词

---

## 自进化机制

```
踩坑 → 修好 → /rag-review 末尾追加
    ↓
判断类型：
  - 报错类 → 追加到本文件"快速检索表" + "详细排查"
  - 设计类 → 追加到 rag_design_patterns.md
  - 标准类 → 追加到 rag_evaluation_standards.md
    ↓
同一模式出现 ≥3 次 → 晋升到 rag-knowledge/rules/rag_hot_rules.md
```

## 维护规则

- 每条一行，控制在一屏内
- 超过 30 条 → 合并同类项，归档低频到文末折叠区
- 每次追加注明日期

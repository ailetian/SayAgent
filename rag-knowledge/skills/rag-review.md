---
name: rag-review
description: RAG项目搭建后：代码审查→自动评测→改进建议+自进化
---

# /rag-review — RAG 项目搭建后审查

> 代码写完后触发。自动审查代码质量 → 跑评测 → 对比验收标准 → 输出改进建议 → 触发自进化。

---

## 阶段1：代码审查

逐项检查项目代码，对照 `rag-knowledge/docs/rag_checklist.md` 和 `rag-knowledge/docs/rag_error_patterns.md`。

### 检查清单

```
[1] 文档加载
    - 是否覆盖了项目需要的所有文档格式？
    - 加载失败时是否有错误提示？

[2] 文档切块
    - chunk_size 是否与场景匹配？（参考 rag-knowledge/docs/rag_design_patterns.md）
    - overlap 是否合理？（通常为 chunk_size 的 10~15%）
    - Markdown 文档是否用了标题切分（保留章节结构）？

[3] Embedding
    - 是否复用了已有的 Embedding 模型而非重复加载？
    - 模型维度是否与向量库匹配？

[4] 检索逻辑
    - 是否有相似度阈值过滤？阈值是否合理？
    - 是否有兜底拒答（检索结果太差时说"不知道"）？
    - 混合检索时 BM25 是否配置了中文分词（jieba）？

[5] Query Rewriting
    - 用户口语是否做了书面语转换？
    - 多轮对话时是否做了指代消解？

[6] 错误处理
    - LLM 调用是否有 try/except？
    - 检索失败时是否有降级策略？
    - 向量库不可用时是否有提示？

[7] 安全
    - 是否过滤了用户输入中的敏感指令（Prompt Injection）？
    - 检索结果中是否可能泄露敏感信息（身份证/手机号/工资）？

[8] 配置管理
    - 关键参数（TOP_K、阈值、chunk_size）是否集中管理？
    - API Key 是否通过环境变量加载（不硬编码）？
```

每项标记：通过 / 需改进 / 缺失。

---

## 阶段2：自动评测

### 2.1 检索质量评测

如果项目中有 benchmark.py 或类似评测脚本：

```
跑 benchmark.py → 输出：
  - 召回率@5: [值] / 目标: [值] → [通过/不通过]
  - MRR:      [值] / 目标: [值] → [通过/不通过]
  - NDCG@5:   [值]（参考值）
```

如果没有 benchmark.py，从 `rag-knowledge/snippets/rag_snippets.py` 中复制评测模板。

### 2.2 生成质量评测（RAGAS）

如果有测试用例：

```
跑 RAGAS 评测 → 输出：
  - Faithfulness:       [值] / 目标: [值] → [通过/不通过]
  - Answer Relevancy:   [值] / 目标: [值] → [通过/不通过]
  - Context Precision:  [值] / 目标: [值] → [通过/不通过]
  - Context Recall:     [值] / 目标: [值] → [通过/不通过]
```

测试用例不足时，提示用户：可以用 LLM 从文档自动生成 QA 对（见 `rag-knowledge/snippets/rag_snippets.py`）。

### 2.3 性能评测

如果对延迟有要求：

```
并发测试（Locust 或简单并发脚本）：
  - P50 延迟: [值]
  - P95 延迟: [值] / 目标: [值] → [通过/不通过]
  - P99 延迟: [值]
  - 最大 QPS:  [值]
```

---

## 阶段3：综合报告

### 通过项
列出所有通过的检查项和指标。

### 需改进项
按优先级排列：

```
[高优] 影响准确率或安全 → 建议立即修复
[中优] 影响用户体验或性能 → 建议下个迭代修复
[低优] 代码规范或可维护性 → 可延后
```

每项附带具体的改进建议和参考文档位置。

### 后续优化路线

根据当前评测结果，建议接下来的优化方向：

```
如果召回率低 → 优先优化切块策略，考虑混合检索
如果 Faithfulness 低 → 优化 prompt，加强"严格按文档回答"的约束
如果延迟高 → 检查 Embedding 模型加载、考虑缓存
如果拒答率高 → 降低阈值，或增加 Query Rewriting 的泛化能力
```

---

## 阶段4：自进化触发

审查结束后，主动询问：

```
本次审查过程中，是否遇到了新的问题或意外情况？

如果有：
  1. 报错/故障 → 我将追加到 rag-knowledge/docs/rag_error_patterns.md
  2. 新的设计经验 → 我将追加到 rag-knowledge/docs/rag_design_patterns.md
  3. 评测标准调整 → 我将更新 rag-knowledge/docs/rag_evaluation_standards.md
  4. 可复用代码 → 我将追加到 rag-knowledge/snippets/rag_snippets.py

高频问题 → 自动晋升到 rag-knowledge/rules/rag_hot_rules.md
```

如果用户提供了新发现，自动：
1. 分析内容类型
2. 追加到对应的 docs/ 文件
3. 判断是否需要晋升到 rules
4. 展示追加结果

---

## 参考文档

| 文件 | 用途 |
|------|------|
| `rag-knowledge/docs/rag_checklist.md` | 代码审查详细清单 |
| `rag-knowledge/docs/rag_error_patterns.md` | 已知错误模式，审查时逐一对照 |
| `rag-knowledge/docs/rag_design_patterns.md` | 参数推荐值，审查时用于比对 |
| `rag-knowledge/docs/rag_evaluation_standards.md` | 评测指标定义和计算方法 |
| `rag-knowledge/snippets/rag_snippets.py` | 评测脚本模板，可复制到项目 |

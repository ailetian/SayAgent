# RAG 知识库 — 使用手册

> 从零到验收，一条完整的 RAG 项目开发流程。

---

## 这个目录能做什么？

```
你: "我要做一个客服知识库"
     → /rag-init 问你 6 个问题 → 输出架构方案 + 验收标准
     → 写代码
     → /rag-review 审查代码 → 跑评测 → 告诉你哪里要改
     → 遇到新坑 → 自动记录 → 下次自动规避
```

**不是一本文档，是一套交互式流程。** 文档是参考资料，技能是导航系统。

---

## 场景1：启动一个全新的 RAG 项目

### Step 1：复制到新项目

```bash
cp -r rag-knowledge/ ~/my-rag-project/
cd ~/my-rag-project
```

### Step 2：注册（只做一次）

按照 `SETUP.md` 的说明，在 `.claude/settings.local.json` 中注册两个技能，在 `AGENTS.md` 中引用规则。

### Step 3：触发搭建引导

在 Claude Code 中输入：

```
/rag-init
```

AI 会逐个问你 6 个问题：

```
Q1: 知识库用途？         → 你选 [2] 客服知识库
Q2: 预计文档量？         → 你选 [3] 大型（1000~10000篇）
Q3: 查询类型？           → 你选 [3] 两者都有
Q4: 准确率要求？         → 你选 [2] 标准
Q5: 并发量？             → 你选 [2] 中（10~100 QPS）
Q6: 文档更新频率？       → 你选 [2] 月度更新
```

然后 AI 输出：

```
推荐方案：
  切块策略: chunk_size=500~800, overlap=80~120
            Markdown用标题切，纯文本按段落切
  检索引擎: 混合检索（BM25+向量+RRF融合）
            QPS较高，考虑加缓存
  需要 Query Rewriting: 是（客服口语化提问多）
  需要 Rerank: 建议加（文档量大，粗筛→精筛更可靠）

验收标准（业务系统级）：
  召回率@5: ≥90%  |  Faithfulness: ≥85%
  MRR: ≥0.85      |  P95延迟: ≤3s
  ...（完整指标表）

推荐项目结构：
  your-project/
  ├── data/          ← 把文档放这里
  ├── main.py        ← 从 templates/enterprise_rag/ 复制
  ├── config/
  ├── services/
  └── ...
```

**你需要确认或调整参数，然后就可以开始写代码。**

### Step 4：写代码

两种方式：

**方式A：用模板起步（推荐）**
```bash
cp -r rag-knowledge/templates/enterprise_rag/* .
# 修改 config/settings.py 的参数
# 在 services/qa_service.py 中填业务逻辑（核心链路已经写好了）
```

**方式B：从 snippets 拼装**
```python
# 从 rag-knowledge/snippets/rag_snippets.py 中复制需要的函数
# 片段1: build_bm25_retriever
# 片段2: rrf_fusion
# 片段3: rewrite_query
# ...
```

### Step 5：验收

代码写完后：

```
/rag-review
```

AI 会自动：

1. **逐项检查代码** — 对照 30+ 项清单（切块参数对不对？BM25 加 jieba 了吗？有没有拒答机制？）
2. **跑评测** — 如果你有 benchmark.py → 自动跑并对比验收标准
3. **输出报告** — 通过的项 / 需要修的项（按优先级排列）

```
审查报告：
  通过: 12/15
  需改进:
    [高优] BM25 未配置 jieba 中文分词
    [中优] 缺少 Prompt Injection 过滤
    [低优] 建议把 TOP_K 和阈值移到 config.py
```

---

## 场景2：已有 RAG 项目，想做质量检查

直接在新项目中复制 rag-knowledge/ 并注册，然后：

```
/rag-review
```

AI 会审查已有代码 + 跑评测 + 出报告。不需要先跑 /rag-init。

---

## 场景3：遇到新坑，沉淀到知识库

```
开发中踩坑 → 修好 → /rag-review 末尾会问：

  "本次是否遇到了新的问题？"
  你: "BM25在处理中英文混合文档时，分词器需要先判断语言再选择策略"

AI 自动：
  1. 分析 → 属于设计模式类
  2. 追加到 rag-knowledge/docs/rag_design_patterns.md 的"自进化记录"
  3. 提示: "这条经验是否需要晋升为高频规则？"（你说要→追加到 rules/rag_hot_rules.md）
```

**你只需要描述问题，AI 负责分类、格式化、写入、判断是否晋升。**

---

## 核心文件速查

| 我要... | 看这个 |
|---------|--------|
| 启动新项目，定方案 | `/rag-init` (或看 `skills/rag-init.md`) |
| 检查代码质量 | `/rag-review` (或看 `skills/rag-review.md`) |
| 代码报错了 | `docs/rag_error_patterns.md` — 先查快速检索表 |
| 不知道切块设多大 | `docs/rag_design_patterns.md` — 第1节 切块策略决策树 |
| 不知道选什么检索引擎 | `docs/rag_design_patterns.md` — 第2节 检索引擎选择 |
| 不知道验收标准设多少 | `docs/rag_evaluation_standards.md` — 第5节 速查表 |
| 上线前最后检查 | `docs/rag_checklist.md` — 逐项打勾 |
| 要一段现成的 BM25+RRF 代码 | `snippets/rag_snippets.py` — 片段1+2 |
| 要完整的项目脚手架 | `templates/enterprise_rag/` |
| 想知道怎么把知识库装到新项目 | `SETUP.md` |
| AI 写 RAG 代码想让它自动规避已知坑 | `rules/rag_hot_rules.md`（AGENTS.md已引用） |

---

## 自进化示例

```
第1个项目：发现 BM25 中文分词问题 → 追加到 error_patterns + hot_rules
第2个项目：AI 写 BM25 代码时自动加了 jieba（你不需要提醒）
第2个项目：发现 PDF 表格识别不准 → 追加到 error_patterns
第3个项目：AI 在评测时自动检查了 PDF 表格质量
第3个项目：发现高并发下 Chroma 锁冲突 → 追加 + 晋升为 hot_rules
第4个项目：AI 在 /rag-init 时根据并发量自动推荐了 Qdrant（而非 Chroma）

... 知识库越用越聪明
```

---

## FAQ

**Q: 必须用 LangChain 吗？**
A: 技能流程和知识库文档与框架无关。`snippets/` 和 `templates/` 目前是 LangChain 版本，后续可贡献 LlamaIndex 版本。

**Q: 小项目（<50条文档）也需要这套吗？**
A: 技能流程值得走一遍（/rag-init 5分钟），但模板可能过重。至少把 `rag_hot_rules.md` 挂上，让 AI 自动规避基础坑。

**Q: 已有项目怎么接入？**
A: 复制 rag-knowledge/ → 注册技能 → 直接 `/rag-review`，不用 `/rag-init`。

**Q: 怎么确认技能已生效？**
A: 输入 `/rag-init` 或 `/rag-review`，看到 AI 按技能文件中的流程回应即表示生效。

**Q: 多个项目怎么共享同一份知识库？**
A: 把 rag-knowledge/ 放在一个独立仓库，每个项目通过 git submodule 或软链接引用。所有项目的踩坑都追加到同一份文件。

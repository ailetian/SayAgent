# 企业级 RAG 项目模板

> 基于 `rag-knowledge/` 知识库的最佳实践，开箱即用。

## 快速启动

```bash
# 1. 安装依赖
pip install langchain langchain-deepseek langchain-huggingface langchain-chroma
pip install langchain-community langchain-text-splitters jieba ragas

# 2. 配置
cp .env.example .env
# 编辑 .env 填入 API Key 和 Embedding 模型路径

# 3. 拉代码片段（Review 时用）
cp ../../snippets/rag_snippets.py ./benchmark.py

# 4. 初始化向量库
python main.py --init

# 5. 启动
python main.py
```

## 目录结构

```
enterprise_rag/
├── main.py                   ← 主入口（交互/API 两种模式）
├── config/
│   ├── settings.py           ← Pydantic Settings 集中配置
│   └── rag_config.yaml       ← 业务参数（切块/检索/生成）
├── core/
│   ├── loaders/router.py     ← 多格式加载路由
│   ├── splitters/router.py   ← 多策略切块路由
│   ├── retrieval/            ← 检索模块
│   ├── rewriter/             ← Query Rewriting
│   └── generation/           ← 生成模块
├── services/
│   └── qa_service.py         ← 完整 QA 链路编排（填好了）
├── models/
│   └── qa.py                 ← 请求/响应模型
├── evaluation/
│   ├── accuracy/
│   │   ├── ragas_runner.py   ← RAGAS 四指标评测
│   │   └── test_cases.json   ← 评测用例
│   └── performance/
│       └── benchmark.py      ← 并发压测
├── api/                      ← FastAPI 入口
├── scripts/
│   └── init_vectorstore.py   ← 初始化脚本
├── docker/
├── .env.example
└── requirements.txt
```

## 与 rag-knowledge 的关系

本模板是 `rag-knowledge/` 知识库的"代码落地版"：

- 搭建前：`/rag-init` → 确定方案和验收标准
- 搭建时：复制本模板 → 填业务逻辑
- 搭建后：`/rag-review` → 审查 + 评测 + 自进化

# RAG 知识库 — 可复用技能包

> 复制到任何新 RAG 项目，提供：搭建前引导 + 搭建后审查 + 自进化知识库

## 入口文档

| 文档 | 读它做什么 |
|------|-----------|
| **[GUIDE.md](GUIDE.md)** | **使用手册——完整的端到端流程、场景示例、FAQ** |
| [SETUP.md](SETUP.md) | 新项目接入步骤（2分钟） |
| [README.md](README.md) | 本文件——目录结构和设计原理 |

## 快速开始

```
1. 复制整个 rag-knowledge/ 到新项目根目录
2. 看 GUIDE.md 了解完整用法
3. 看 SETUP.md 完成注册（2 分钟）
4. /rag-init    → 启动搭建引导
5. /rag-review  → 启动审查评测
```

## 目录结构

```
rag-knowledge/
├── README.md                           ← 本文件
├── SETUP.md                            ← 新项目接入指南
│
├── skills/                             ← 技能定义（交互流程）
│   ├── rag-init.md                     ← 搭建前：场景分析→架构推荐→验收标准
│   └── rag-review.md                   ← 搭建后：代码审查→评测→改进建议
│
├── docs/                               ← 共享知识库（技能引用）
│   ├── rag_error_patterns.md           ← 错误模式库·自进化
│   ├── rag_design_patterns.md          ← 设计模式·自进化
│   ├── rag_evaluation_standards.md     ← 评测标准与方法
│   └── rag_checklist.md                ← 上线前检查清单
│
├── snippets/
│   └── rag_snippets.py                 ← 可复用代码片段
│
├── rules/
│   └── rag_hot_rules.md                ← RAG 高频规则（AI 始终加载）
│
└── templates/
    └── enterprise_rag/                 ← 企业级 RAG 项目骨架
```

## 自进化机制

```
开发中踩坑 → 修好 → /rag-review 末尾追问
    ↓
AI 分类路由：
  报错类 → rag_error_patterns.md（快速检索表）
  模式类 → rag_design_patterns.md（设计决策）
  标准类 → rag_evaluation_standards.md（评测阈值）
  代码类 → snippets/rag_snippets.py（可复用片段）
    ↓
高频模式 → 晋升到 rules/rag_hot_rules.md（AI 下次自动生效）
```

## 两个技能的职责

| | /rag-init | /rag-review |
|------|-----------|-------------|
| **时机** | 新项目，还没写代码 | 代码写完，准备验收 |
| **输入** | 你的业务需求描述 | 已有代码 + 评测数据 |
| **输出** | 架构方案 + 验收标准 | 审查报告 + 改进建议 |
| **衔接** | 定的标准 → | ← 拿来评分 |

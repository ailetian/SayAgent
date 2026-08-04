# RAG 知识库 — 新项目接入指南

> 复制本目录到新项目后，按以下步骤完成注册，2 分钟即可。

## 步骤1：复制到新项目

```bash
cp -r rag-knowledge/ ~/my-new-rag-project/
```

## 步骤2：注册技能

在新项目的 `.claude/settings.local.json` 中添加：

```json
{
  "skills": {
    "rag-init": {
      "description": "RAG项目搭建前：场景分析→架构推荐→验收标准制定",
      "path": "rag-knowledge/skills/rag-init.md"
    },
    "rag-review": {
      "description": "RAG项目搭建后：代码审查→自动评测→改进建议+自进化",
      "path": "rag-knowledge/skills/rag-review.md"
    }
  }
}
```

如果已有其他 skill，合并到 `skills` 对象中即可，不要覆盖已有条目。

## 步骤3：引入规则

在项目的 `AGENTS.md` 中添加：

```markdown
@see rag-knowledge/rules/rag_hot_rules.md
```

## 步骤4：验证

在 Claude Code 中分别输入：

```
/rag-init
/rag-review
```

应该能看到技能被激活。

## 依赖说明

技能运行依赖以下 Python 包（按需安装，不强制全部）：

| 包 | 用途 | 何时需要 |
|----|------|----------|
| `ragas` | RAG 评测指标计算 | 跑 /rag-review 的自动评测 |
| `jieba` | BM25 中文分词 | 使用混合检索时 |
| `locust` | 并发压测 | 性能验收时 |
| `langchain` 系列 | RAG 核心框架 | 始终需要 |

安装命令：
```bash
pip install ragas jieba locust
```

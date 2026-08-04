---
name: task-dev-pitfalls
description: 在 hify_python 仓库按子任务（Mx/Ty）做 Java 开发、写单测、写 Flyway 迁移/DDL、处理鉴权与秘钥隔离时加载本 skill。它只沉淀 AGENTS.md 未覆盖的实战坑与项目特定陷阱（write_to_file 偶发写空、包路径错配、ErrorCode 复用、Retry 语义、秘钥脱敏、软删约定等），并自动把新坑回写本 skill 持续进化。规范原文以 AGENTS.md 为准，本 skill 不重复。
---

# 任务开发实战坑位（hify_python）

本 skill 只记 **AGENTS.md 未覆盖** 的踩坑与项目特定陷阱，做到"同样的坑只踩一次"。规范细节（命名/脱敏/模板）以 AGENTS.md 原文为准，不在此复述。

## When to use
做任务开发（写代码/单测/DDL/鉴权/秘钥）或验收前自查时；出现"又踩到类似坑"的迹象先查 `references/pitfalls.md`。

## 使用
开发前 grep `references/pitfalls.md` 关键字（如 `Retry`、`write_to_file`、`@JsonIgnore`）带入本次任务；交付前逐项自检。

## 自进化协议
每次子任务开发完成（代码+单测+编译通过）后：回顾是否出现清单外的新坑？若有，按 A/B/C/D 分类在 `references/pitfalls.md` 末尾用 `replace_in_file` 追加一条（格式：`### Xn. 标题` + 一行 `- 坑：...｜正：...｜来源：<任务> (<日期>)`）；不准确则更新原条，不删旧条、不建重复条。触发信号：用户说"又踩坑/禁止再出现"、验收被打回、离奇工具/编译错误、历史条描述过时。

## Resources
- `references/pitfalls.md`：坑位清单（A 规范合规 / B 工具构建 / C 代码架构 / D 验证交付），按需 grep。

---
name: 子模块任务拆分验收
description: 当用户要求按 CLAUDE.md 严格验收某个大模块（Mn，如 M3）的子任务拆分文档时使用。以 CLAUDE.md 为唯一基准、以真实仓库为真相来源，对 plans/Mn/ 下的 00_概述.md 与 Tn_*.md 做逐条合规验收，沉淀并复用验收踩坑清单（构建工具、Flyway 版本、§6.1 DDL 模板、CLAUDE.md 章节号错位、包/路径、三方一致性、误报纪律）。同样适用于后续 M4/M5/M6/M7 等模块的验收。
---

# 子模块任务拆分验收 SOP

对"子模块任务拆分"skill 产出的 `plans/Mn/` 文档做合规验收，确保其与 `CLAUDE.md` 规范、与 `backend` 真实仓库状态三方一致，方可进入编码。

## 何时使用
- 用户说"按 CLAUDE.md 验收 M?""验收下 M? 任务划分""检查 Mn 拆分是否合规"。
- 配套：`子模块任务拆分` skill 负责"产出文档"，本 skill 负责"验收文档"，二者互补。

## ⚠️ 与「任务代码审核」的边界（别混用）
- **本 skill = 计划文档阶段**：编码前，验收 `plans/Mn/*.md` 计划文档是否合规（章节号、Flyway 版本、包路径、DDL 模板…）。
- **「任务代码审核」skill = 代码已合入后**：编码后，审核仓库真实代码是否违规（test green ≠ 合规、测试连真库、软删未生效、注释错引章节…）。
- 一句话：**文档还没写代码 → 用本 skill；代码已 merge → 用「任务代码审核」。** 前者查"任务书对不对"，后者查"代码对不对"。

## 三条铁律（验收前先刻进脑子）
1. **CLAUDE.md 是唯一基准**：所有规则以仓库根目录 `CLAUDE.md` 的当前文本为准，不依赖记忆或旧编号。
2. **真实仓库是真相来源**：`backend/` 的实际文件（pom.xml / build.gradle、迁移版本号、package 声明、实体字段）说了算，计划文档只是"待验证的声明"，可被文档带偏。
3. **三方必须一致**：代码（仓库真实态） / 计划验收点（文档声明） / CLAUDE.md 规范 任一脱节即不通过。

## 验收流程（严格按顺序）
1. **读基准**：完整读取 `CLAUDE.md`，记录本次相关的 § 号与模板（尤其 §3 依赖/包、§4 配置、§5 依赖、§6 DDL、§7 规则、§8 测试、§9 日志）。
2. **探真相**：用工具核实仓库真实态——
   - `list_dir backend` + 看根：有 `pom.xml` 无 `build.gradle` ⇒ Maven；反之 Gradle。**额外确认是否有 `mvnw`**（本仓库无 ⇒ 命令写 `mvn`，绝写 `../mvnw`/`gradlew`）。
   - 列 `backend/src/main/resources/db/mysql/migration/`：得到**已占用 Flyway 版本号**，确定新脚本应顺延到的号（MySQL 单序列，禁止占位撞车）。
   - 若有 `db/pg/migration/`：Pg 为**独立序列**，版本号与 MySQL 分开计数（注意 `initdb` 的 V1 前置）。
   - `search_content` 抽真实 `package com.hify...` 声明与目录，确认基包与子包路径。
3. **读文档**：读取 `plans/Mn/00_概述.md` 与全部 `Tn_*.md`。
4. **逐条核验**：对每个 Tn 的 8 段结构（见 `子模块任务拆分` skill），核对——
   - 构建命令（`./gradlew` ↔ `../mvnw`）
   - Flyway 版本号是否撞车已占用版本
   - DDL 是否含 `idx_created_at` 且对齐 §6.1 模板
   - 每个 `§x.y` 引用：编号是否真实存在 **且主题是否匹配**（打开 CLAUDE.md 定位标题/正文，确认该条款讨论的就是文档声称的主题；紧盯"猜章节"——编号对但主题错，如 §7.8 当持久层、§4.5 当枚举、§3.3 当配置、§7.4 当异常、§4.9 当线程池、§7.5 当规则23）
   - 包/目录路径是否与仓库 `package` 声明一致
   - 异常/错误码/类名是否臆造：用 `search_content` 检索计划文本的 `AppException`/`BizCode` 等本仓库不存在的符号；凡引用仓库 `search_content` 搜不到的类/枚举，判不通过（对照 `common/exception/` 真实类 `BizException`/`ErrorCode`，见 CLAUDE.md §3.5/§7.3）
   - 验收点是否均为可执行断言、是否与实际文件对得上
5. **复验清零**：用 `search_content` 在 `plans/Mn/` 与 `plans/0X_Mn_*.md` 全量检索整改关键词（gradle / 旧 § 号 / 错误包名 / V3 等），确认零残留。
6. **出报告**：产出 `plans/Mn_CLAUDE.md验收报告.md`，列「问题→文件→原句→应改为→依据 §」，并给"通过/整改后通过/不通过"结论。

## 防误报纪律（来自实战教训，务必遵守）
- **大批量读文件后必须 re-read 关键文件再下结论**。上下文被截断/串味时，容易凭空声称文档用了错误包或路径（曾误报 `com.hify.llm`、`deploy/mysql/V3`）。凡要写入"违规"，先 `search_content` 确认仓库/文档**确实含该字符串**。
- 报告若已发布且被发现误报，**主动勘误并致歉**，不要将错就错。

## 常见坑位速查（完整沉淀见 references/验收踩坑沉淀.md）
| 类别 | 典型错误 | 验收动作 |
|------|----------|----------|
| 构建工具 | 文档写 Gradle/`./gradlew` 或 `../mvnw`，仓库实为 Maven 且**无 mvnw** | 看根确认 pom.xml；命令统一用 `mvn`（系统 Maven，仓库无 mvnw wrapper） |
| Flyway 版本 | 新脚本写 V5/V6/V7/V8 撞车（顺延链：M4=V5、M5=V6、M6=V7/V8、M7=V9） | 列 `mysql/migration` 定占用号顺延；Pg 独立序列走 `db/pg/migration/`（参考 V1=initdb） |
| §6.1 DDL | 建表漏 `KEY idx_created_at (created_at)`（与 V2 同类，明令禁复发） | 每个 `CREATE TABLE` 查 idx_created_at + 模板项 |
| 章节号错位 | 引用失效旧号（§6.3/§6.5/§6.6/§7.5/§7.9/§9/§8…） | 每条 `§x.y` 打开 CLAUDE.md 实定位核对 |
| 章节主题错位（猜章节） | 引用**编号有效但主题错**：§7.8当持久层/§4.5当枚举/§3.3当配置/§7.4当异常/§4.9当线程池/§7.5当规则23 | 每条先核对"该条款真实主题"，不能只看编号存在（M5 系统性踩） |
| 包/路径错 | `com.hify.llm`、`/llm/`、`web/vo/`、`db/migration/mysql`（反序） | 对照真实 `package` 与目录；迁移路径 `db/mysql/migration/`+Pg `db/pg/migration/` |
| 三方脱节 | 验收点声明与仓库文件对不上 | 每条验收点既查文档也查仓库 |
| 臆造符号（类名/异常） | 计划写 `AppException(BizCode.XXX)`，仓库实为 `BizException(ErrorCode.XXX)`（CLAUDE.md §3.5/§7.3） | 检索计划文本 `AppException|BizCode`，凡命中即判不通过，改 `BizException(ErrorCode.XXX)` |

## 验收清单（交付报告前逐条确认）
- [ ] 已读 `CLAUDE.md` 当前文本，未用记忆中的旧编号。
- [ ] 已确认构建工具（Maven/Gradle）以仓库为准。
- [ ] 已列 migration 目录，新脚本版本号无撞车。
- [ ] 每个 `CREATE TABLE` 含 `idx_created_at` 且对齐 §6.1。
- [ ] 文档每条 `§x.y` 已打开 CLAUDE.md 实定位核对。
- [ ] 文档包/路径与仓库真实 `package` 声明一致。
- [ ] 每条验收点均为可执行断言且对得上实际文件。
- [ ] 全局检索零残留旧标记（gradle/旧 § 号/错包名/V3）。
- [ ] 报告给出明确结论，误报已勘误。
- [ ] 计划文本已 `search_content` 检索 `AppException|BizCode` 等臆造符号，零命中（异常统一 `BizException`、错误码统一 `ErrorCode`，见 CLAUDE.md §3.5/§7.3）。

## 资源
- `references/验收踩坑沉淀.md`：M3 验收实战沉淀的详细坑位、根因与排查命令，验收前通读。

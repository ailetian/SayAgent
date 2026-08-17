# SayAgent 项目面试总结 · 智能体开发工程师

> **使用说明**：这是一份"能讲透、可辩护"的项目总结，面向「智能体开发工程师」岗位。
> 所有内容基于真实源码（`backend/src/main/java/com/hify/hify/...`）与验收记录（`plans/K/`），按既定叙事策略撰写：
> **只陈述事实与扩展，不提"培训/教程"来源；不虚构"纯原创从零"，被问到技术选型诚实讲"基于成熟参考架构（Dify 类产品的设计范式），自己做了 X 扩展、修了 Y bug"。**

---

## 0. 电梯陈述（30 秒版本，背下来）

> 我独立做了一个**自托管的企业级 AI Agent 平台 SayAgent**，可以理解为「Java 版的 Dify」。它让一个管理员配置模型、知识库、MCP 工具，业务同学就能在网页上拥有"懂公司文档、能操作内部系统"的 AI 助手。后端 Spring Boot 3.3 + Java 21，前端 Vue 3，AI 用 LangChain4j + MCP Java SDK；存储是 MySQL + PostgreSQL/pgvector + Redis。我完整跑通了 **RAG 混合检索、Agent 工具编排循环、MCP 工具发现与调用降级、流式对话、对话轨迹留痕**这几条主链路。

---

## 1. 项目定位与动机（开场讲清"为什么做"）

- **定位**：「给团队自己用的 AI 员工制造厂」——一人搭建平台，团队 20–50 人都能拥有"懂文档 + 能操作系统"的 AI 助手。
- **三个核心目标（我自己能讲清为什么这么定）**：
  1. 练出全栈 AI 平台真本事（LLM 治理 / RAG / Agent / MCP / 流式 / 部署全链路）；
  2. 完全自主可控（代码、数据、模型皆在自手，不绑定外部 SaaS 能力边界）；
  3. 支持后期深度定制与维护（随真实使用做精细化扩展）。
- **关键决策**：Java 的 AI 生态弱于 Python，但以"工程化稳健 + 单人可控"优先，AI 短板用 LangChain4j + MCP Java SDK 兜底。

---

## 2. 技术栈（一图说清）

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3.5 + Java 21，模块化单体（Modulith，按功能分包） |
| AI 框架 | LangChain4j + MCP Java SDK |
| 前端 | Vue 3 + Vite + Element Plus，SSE 流式对话 |
| 业务库 | MySQL 8（Flyway 迁移） |
| 向量库 | PostgreSQL + pgvector（BGE-M3，本地 Ollama，1024 维） |
| 缓存/限流 | Redis |
| 检索 | PG 全文检索（zhparser 中文分词）+ pgvector 向量，RRF 融合 + 阈值拒答 |
| 模型 | LLM=DeepSeek；Embedding=BGE-M3（本地 Ollama） |
| 韧性 | Resilience4j（熔断 / 重试 / bulkhead / 限流） |
| 部署 | Docker / K8s，docker-compose 一键起 |

---

## 3. 架构：模块化单体（Modulith，这是我的工程治理重点）

按功能分包：`common` / `modelprovider` / `agent` / `conversation` / `knowledge` / `workflow` / `mcp`。

**解耦纪律（面试可展开讲，体现架构判断力）**：
- 分层：`controller`（参数校验+响应封装）→ `service`（业务逻辑）→ `repository`（数据访问）；**禁止 controller 直接调 repository**。
- 跨模块只依赖接口（`XxxService` / `XxxApi`），**禁止直接 import 其它模块的内部类**（entity/repository/impl）；出现循环依赖即视为错误，必须重构（抽公共接口到 `common` 或事件解耦）。
- `common` 禁止反向依赖任何业务包；`common/tool/` 定义统一工具契约（`Tool` / `ToolDefinition` / `ToolResult` / `ToolCall` + `BuiltinToolRegistry`），跨 mcp/skill/conversation 共享。

---

## 4. 我重点做 / 能讲透的模块

### 4.1 RAG 知识库 —— 混合检索 + RRF + 自适应切片 + 阈值拒答（核心亮点）

- **支持格式**：TXT / MD / PDF（文本层）/ DOCX / .doc（Tika 解析器族：`TikaPdfParser` / `TikaDocxParser` / `TikaDocParser`）。
- **两路召回**：PG 全文检索（`zhparser` 中文分词，找"字面对"的）+ pgvector 向量（找"语义近"的），各取 top-k。
- **RRF 融合**：双路分数单位不可比，只投"名次"——`score = 1/(rrf_k + rank)` 求和再排序。**`rrf_k = 60`**（已在 `RagConfig` 与单测 `EXPECTED_RRF_K=60` 固化）。
- **阈值拒答**：融合后统一过 `scoreThreshold` 拒答，避免幻觉。
  - ⚠️ **一个真实坑（能讲出深度）**：RRF 名次分量级约 `0.03`，**不能与 `score_threshold`(如 0.6) 直接比较**，否则会恒拒答。这是典型的"test green ≠ 合规"陷阱，我在 `RetrievalResult` 上专门加了类型注释与对齐约束来防。
- **检索唯一入口铁律（工程治理）**：所有检索走 `knowledge.service.KbRetrievalService`，probe / eval / ask / 聊天一律委托它，**严禁在调用点 copy `retrievalPort.retrieveHybrid` 或 `kb.getEffectiveConfig`** —— 避免"探针能查、聊天查不到"的阈值不一致 bug。
- **查询增强**：`QueryIntentClassifier`（意图分类）+ `QueryRewriter`（查询改写）提升召回质量；`RagQueryService` 编排整条查询链路。
- **切片**：`DocumentChunker` + `ChunkStrategy` 自适应切片（按语义/结构边界，而非固定长度）。
- **评测**：`EvalRunner` + `EvalMetrics`（MRR / nDCG@k / P95 延迟），用 `EvalDataset` 做回归。

### 4.2 Agent 编排循环 —— ToolLoopRunner：思考 → 执行 → 反思

- 对话引擎用 **SSE** 流式返回（`ConversationController` 返回 `SseEmitter`）。
- `conversation.tool.ToolLoopRunner` 实现"**思考 → 执行 → 反思**"循环：模型决策调用哪个工具 → 执行 → 把结果拼回上下文再生成，直到结束或达上限。
- `ToolRegistry` 把 Agent 配置的 `toolRefs` 解析成可用工具列表；`ToolStepSink` 是进度出口（前端实时看到"正在调用工具"）。
- 工具调用失败**一律留痕且不中断**（降级返回提示而非抛 500）。

### 4.3 MCP 集成 —— 工具自动发现 + 双传输 + 超时与降级

- 管理员配 MCP Server 地址 → `McpClientManager` 自动 `listTools` 发现工具 → Agent 调用内部系统。
- **完整 JSON-RPC 客户端**：`initialize` / `tools/list` / `tools/call`；协议版本 `2024-11-05`。
- **双传输**：HTTP Streamable（POST JSON-RPC，响应可为 `application/json` 或 `text/event-stream`）+ STDIO（拉起子进程，标准输入输出传 JSON-RPC）。
- **两级超时**：复用 OkHttp 单例，每次调用派生"连接/读超时"子类（连接池共享）；专用 `mcpExecutor` 线程池隔离，**禁止在 DB 事务内调用**。
- **失败降级**：所有失败统一抛 `BizException.MCP_CALL_FAILED`，由上层 T3 降级，**绝不抛 500 中断对话**。

### 4.4 提示词式 Skill —— 能力复用，与 MCP / 知识库正交

- `skill` = 可复用的**提示词式指令块**（不是工具）。
- `Skill.promptText` + `SkillService.composePersona` 静态拼进 Agent 人设；多对多复用。
- **三者正交**：MCP / 内置工具 = 执行动作；知识库 = 检索上下文；skill = 行为指令复用。skill 不进函数调用列表。

### 4.5 流式 + 对话轨迹留痕（你的硬要求，也是差异化卖点）

- SSE 流式对话，逐 token 推送；前端 `MessageBubble.vue` 解析渲染。
- **知识库检索命中 + 每次 MCP 工具调用一律持久化、不可删除**，挂在每条消息的「调用轨迹」（`message.trace_json`）。前端折叠展开看明细 —— 满足企业"AI 决策可追溯"诉求。

### 4.6 多模型 Provider + 韧性

- 模型管理：管理员手动添加（名称 / API 地址 / 秘钥 / 类型 OpenAI / Claude / Gemini / Ollama），存 MySQL 全公司共用。
- `ProviderClient` 统一接口 + 各厂商 Client + `ResilienceDecorator`（Resilience4j 熔断 / 重试 / bulkhead / 限流）+ `ProviderRouter`。

### 4.7 权限分层

- 登录 + ADMIN / USER 二元角色（Spring Security + JWT）。
- service 层 `isAdmin()` 校验真实生效（AgentService / ModelService / McpServerServiceImpl / SkillService / KbAccessGuard / MessageFeedbackService）。

---

## 5. 我踩过的坑（体现工程能力，每个都能讲清根因 + 解法）

1. **本机无 Maven / 中文路径下 `mvn` 必报错** → 改用「解 fat jar 取 `BOOT-INF/classes` + `BOOT-INF/lib` 作 classpath，直接 `java -cp` 跑单测」，不破坏 Spring Boot 启动器；改 `conversation` 包必须整包一起 javac（Lombok 注解处理导致跨文件解析失败）。
2. **Ollama 默认只听 IPv4，Java 把 localhost 解析成 IPv6 `::1`** → `OLLAMA_HOST=[::]:11434` 双栈启动；并显式设 `OLLAMA_MODELS` 指向真仓库（否则回退空仓库误判"模型没了"）。
3. **聊天硬编码阈值 0.6 导致"探针能查、聊天查不到"** → 立"检索唯一入口铁律"，阈值统一走 `kb.getEffectiveConfig().scoreThreshold()`。
4. **RRF 名次分（≈0.03）与阈值（0.6）单位不可比，直接比会恒拒答** → 在 `RetrievalResult` 加类型注释与对齐约束，检索入口统一取阈值，避免 test green ≠ 合规。
5. **跨库软删下推类型对齐 bug** → `PgVectorRetrievalPort` 用两段式软删下推，修复检索类型对齐（K4 验收点）。

---

## 6. 验收进展（可量化、可辩护）

- RAG 模块 **K1–K16 任务全绿交付**（验收报告在 `plans/K/`，含 K1~K11 主任务 + K12~K16 选型/验证/实施计划）。
- **9095 实例跑通**：V27 迁移 applied，health 200，含 T3（ToolLoopRunner）+ T4（提示词式 skill）。
- 单测覆盖：RRF 融合、跨库软删、阈值对齐、EvalMetrics（MRR / nDCG / P95）均有单测守护。

---

## 7. 诚实的边界 / 下一步规划（别吹，体现架构演进思考）

当前已知缺口（可作为"我下一步会怎么演进"来展示判断力）：

- **无用户管理接口**（`user` 包只有 login + AdminSeedRunner）→ 拉不进 20–50 人真实协作。
- **MCP 实体无鉴权凭据字段** → 只能连免鉴权 MCP；调用不透传登录用户身份 → 工单记不了"谁提的"。
- **工具调用无白名单 / 读写分级 / 二次确认 / 幂等** → 模型选了就执行（安全风险）。
- **Agent 无可见范围字段** → 所有人看到同一份列表。
- **无 ReRanker** → 检索质量靠 RRF + 阈值，后续可接交叉编码器精排。

> 面试话术：「这些不是我不知道，而是首版按 MVP 砍掉的。如果让我带团队做下一步，我会优先补『工具调用安全分级 + 二次确认』和『用户管理 + 可见范围』，因为这两块直接决定能不能真给 20–50 人用。」

---

## 8. 高频追问预设（Q&A 草稿，提前练熟）

**Q：为什么选 Java 而不是 Python 做 AI 平台？**
A：工程化稳健 + 单人可控优先；Java 类型系统与部署成熟度对自托管平台更友好，AI 生态短板用 LangChain4j + MCP Java SDK 兜底。牺牲一点 AI 生态成熟度，换可维护性。

**Q：RRF 怎么融合？权重怎么定？**
A：FTS 和向量各取 top-k，按名次 `1/(60+rank)` 求和排序（k=60 是经验值，平衡头部与长尾）；融合后统一过 `scoreThreshold` 拒答。关键在于双路分数单位不可比，所以只投名次不投原始分。

**Q：MCP 超时怎么设计？为什么不在事务里调？**
A：两级超时（连接/读）复用 OkHttp 单例派生；专用线程池隔离，避免一个慢工具拖垮对话；MCP 调用是远程 IO，放在 DB 事务里会长期占连接，所以强制在事务外、专用池执行，失败降级成提示而非中断。

**Q：怎么防止 Agent 乱调工具 / 越权？**
A：（诚实）目前**还没有**白名单和二次确认，这是已知缺口；下一步会加工具白名单 + 读写分级 + 高危操作二次确认 + 幂等键，并在 MCP 调用里透传登录用户身份。

**Q：为什么 pgvector 而不是 Milvus / Qdrant？**
A：小团队自托管，PG 一体承载业务 + 向量，运维成本最低；数据量在千万级内 pgvector 足够，且能用 SQL 直接做跨库软删下推、与 FTS 同库融合。

**Q：流式怎么实现的？工具进度怎么实时显示？**
A：后端 `SseEmitter` 逐 token 推送；工具进度经 `ToolStepSink` 作为独立 SSE 事件流出，前端实时渲染"正在调用 XX 工具"。

**Q：检索和聊天为什么不能各写一份？**
A：曾经聊天硬编码阈值导致"探针能查、聊天查不到"。所以立了"检索唯一入口铁律"——所有检索走 `KbRetrievalService`，阈值统一取 `kb.getEffectiveConfig().scoreThreshold()`，杜绝双份逻辑漂移。

**Q：你这个项目和 Dify 比，差异化在哪？**
A：Dify 是通用 SaaS/开源平台；SayAgent 是面向"20–50 人小团队自托管"的场景裁剪：对话轨迹强制留痕（可追溯）、检索唯一入口统一治理、MCP 双传输 + 降级、提示词式 skill 正交复用。规模不如 Dify，但治理纪律和"可审计"是我刻意做的。

---

## 9. 简历里怎么写（4 条 bullet，可直接抄）

- 独立设计并实现自托管企业级 AI Agent 平台（Spring Boot 3.3 + Java 21 + Vue 3 + LangChain4j/MCP Java SDK），覆盖 RAG 混合检索、Agent 工具编排、MCP 集成、流式对话全链路。
- 构建 RAG 混合检索管线：PG FTS(zhparser) + pgvector 双路召回、RRF(k=60) 融合、自适应切片与阈值拒答，并设计"检索唯一入口"治理规则避免阈值漂移；单测覆盖 RRF/跨库软删/阈值对齐。
- 实现 Agent 编排循环（思考→执行→反思）与 MCP 客户端（JSON-RPC、HTTP Streamable + STDIO 双传输、两级超时、失败降级不中断），打通"模型决策 → 工具调用 → 结果回灌"闭环。
- 落地对话轨迹强制留痕（KB 命中 + 工具调用可回看）与多模型 Provider 韧性（Resilience4j 熔断/重试/限流），支撑企业级 AI 决策可追溯与稳定性。

---

*附：真实代码入口速查（面试被要求指代码时用）*
- 检索：`knowledge/service/KbRetrievalService.java`、`knowledge/retriever/PgVectorRetrievalPort.java`、`knowledge/retriever/RetrievalPort.java`
- 编排：`conversation/tool/ToolLoopRunner.java`、`ToolRegistry.java`、`ToolStepSink.java`
- MCP：`mcp/McpClientManager.java`
- Skill：`skill/service/SkillService.java`（`composePersona`）
- 配置治理：`knowledge/config/RagConfig.java`（rrfK=60、scoreThreshold）

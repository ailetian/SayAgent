# AGENTS.md — SayAgent 项目规则唯一入口

> **项目规则访问入口（重要）**：本文件（AGENTS.md）是 SayAgent 项目**所有"规则类"约束的唯一入口与单一事实源**（编码 / 数据库 / LLM 调用 / 部署 / 性能等业务规则均在此）。
> - **以后修改任何项目规则，只修改本文件 AGENTS.md**，不要将规则散落到 `01`~`09` 设计文档中。
> - `01_功能需求文档.md` ~ `09_数据库性能规范.md` 是**设计 / 说明文档**（记录"为什么这么定"的背景与结论），**不是规则文件**；若从设计文档提炼出新的硬性约束，请汇总进本文件，保持规则单一收口。
> - 你是本项目的 AI 编码助手：生成或修改任何代码、建表、写配置前，**必须先读本节并严格遵守**。

---

> 配套详细设计文档（位于项目根目录，需了解"为什么"时再查）：`01_功能需求文档.md`、`02_技术选型文档.md`、`03_功能场景通俗说明.md`、`04_外部LLM调用技术方案.md`、`05_部署架构设计.md`、`06_目录结构与依赖清单.md`、`07_性能瓶颈分析.md`、`08_架构图与讲解.md`、`09_数据库性能规范.md`。

---

## 1. 项目概览

- **一句话定位**：SayAgent 是「给团队自己用的 AI 员工制造厂」——一人搭建平台，团队内 20–50 人都能拥有"懂公司文档 + 能操作公司系统"的 AI 助手。
- **核心目标**（开发者 2026-07-20 确认）：
  1. **练出全栈 AI 平台真本事**（LLM 治理 / RAG / Agent / MCP / 流式 / 部署全链路）；
  2. **完全自主可控**（代码、数据、模型皆在自手，不绑定外部 SaaS 能力边界）；
  3. **支持后期深度定制与维护**（随真实使用做精细化扩展）。
- **技术栈**：后端 **Spring Boot（单 Maven 模块 + 按功能分包的模块化单体 Modulith）** + 前端 **Vue 3**；AI 能力用 **LangChain4j / MCP Java SDK**；数据库 **MySQL 8.x（业务） + PostgreSQL+pgvector（向量） + Redis（缓存/限流）**；部署 **Docker / K8s**。
- **约束**：单人全栈开发；内部 20–50 人；本地部署（单租户、自托管）；砍掉一切商业化/生态市场/超大规模/进阶运维复杂度。
- **关键决策**：尽管 Java 的 AI 生态弱于 Python，仍以"工程化稳健 + 单人可控"为优先，AI 短板用 LangChain4j + MCP Java SDK 兜底；开发者暂不懂 Java，由 AI 生成样板代码、开发者审读组装，**所有 Java 术语必须先翻成大白话再讲**。

---

## 2. 核心功能模块（MVP 范围）

**必须做 8 项（主链路：登录 → 配模型 → 建 Agent → 挂知识 → 对话/API → 留日志 → 本地部署 + MCP 接内部系统）**：

| 序号 | 模块 | 说明 |
|---|---|---|
| 1 | 基础登录 + 轻量权限 + 用户管理 | 登录 + JWT；角色升级为 ADMIN/OPERATOR/USER 三档（见 §2.1）；新增后台建用户（选角色）、角色驱动菜单、KB/Agent 按角色+个人混合授权（详见 §2.1 与 `plans/10_M9_用户管理与角色权限.md`） |
| 2 | 模型管理（简化） | 管理员在后台**手动添加**模型：填名称、API 地址、秘钥、类型（OpenAI/Claude/Gemini/Ollama），可配 1–3 个，存 MySQL 全公司共用 |
| 3 | Agent 配置（简化） | 表单式：人设提示词 + 选模型 + 挂知识/工具（不做拖拽画布） |
| 4 | 知识库 + RAG | 文档上传、向量化、检索问答（上传→切分→embedding→pgvector→检索） |
| 5 | 聊天 Web UI + 内部 API | Vue 网页对话 + 供内部调用的 API |
| 6 | 对话日志（简化） | 记录谁/何时/问了啥/耗多少 token（一张日志表） |
| 7 | 单租户本地部署 | docker-compose / K8s 一键起 |
| 8 | MCP 集成 | 管理员配 MCP Server 地址 → Agent 自动发现工具并调用内部系统；**工具调用结果须拼回 Agent 上下文继续生成，且调用失败必须降级**（返回提示而非中断） |

**砍掉 6 项（首版不做）**：可视化 Workflow 画布、插件市场、嵌入发布/公开分享、高级 LLMOps 看板、多租户/计费、知识管道评测台。

**关键简化约定**：Orchestration 用表单/配置页，不做画布；模型管理用配置页，不做供应商市场；MCP 轻量化（配置地址→自动发现工具），不做完整插件市场；**工作流（简版）仅支持 YAML/JSON 配置式顺序定义，禁止引入任何前端拖拽编排库**。

### 2.1 角色与权限模型（深化 M2 / 新模块 M9）

> 解决"按角色显示菜单 + 按角色/个人控制可用知识库与 Agent"。本段为权限规则唯一事实源；详细计划与任务拆分见 `plans/10_M9_用户管理与角色权限.md`。

**1. 角色集（固定三档，不自由建角色）**
- `ADMIN` 平台管理员：模型/MCP/用户/系统配置全权。
- `OPERATOR` 知识运营：可建/管知识库与 Agent，但看不到模型/MCP/用户等平台级配置。
- `USER` 普通成员：仅对话、使用被授权资源，不能自建 KB/Agent。
- 枚举落在现有 `UserRole`（由 `ADMIN/USER` 扩展为 `ADMIN/OPERATOR/USER`）。

**2. 两条正交权限轴（都由角色统一驱动）**
- **功能菜单轴**：`menu_item`(菜单定义) + `role_menu(role_code, menu_code)` → 侧边栏动态渲染；前端登录后拉 `GET /api/me`，路由加 `meta.roles` 守卫。
- **数据资源轴**：KB/Agent 的 `visibility` + 统一 `resource_access` 表做角色/个人混合授权。

**3. 资源授权模型（混合：角色基线 + 个人覆盖）**
- 统一表 `resource_access(principal_type ENUM('ROLE','USER'), principal_id, resource_type ENUM('KB','AGENT'), resource_id, can_read/can_write/can_use/can_edit)`；新增资源类型（MCP/Workflow）不另建表。
- **默认可见性 = RESTRICTED**（secure by default）：新建 KB/Agent 默认仅授权可见；`PUBLIC` 保留为可选值，但不再默认。
- 新建资源的**创建者自动获得**该资源 `resource_access`（principal_type='USER', 本人, 全权），保证自己能继续管理。
- **判定逻辑**：资源对当前用户可见/可用 ⟺ `visibility='PUBLIC'` **或** 存在匹配当前用户(其角色集 / 本人)的 `resource_access` 记录；都不成立则默认不可见。
- `ADMIN` 对所有 KB / Agent **隐式拥有读+写+用+管全权**（平台管理员兜底，不受 RESTRICTED 限制，**无需也不允许**建 `resource_access` 行）；授权操作（T7）**禁止以 ADMIN 角色为授权主体**——前端授权 Tab 角色下拉不含 ADMIN，后端 `ResourceAccessService.grant/revoke` 遇 `principalType=ROLE & principalId=ADMIN` 直接拒绝（`PARAM_INVALID`）。

**4. 用户管理（MVP 范围：建 + 列）**
- `POST /api/users`：`{username, password, role, displayName?, email?}`；`role` 取 `UserRole` 枚举，不传默认 `USER`；`username` 唯一校验；密码走 `PasswordEncoderConfig`(BCrypt) 编码。
- `GET /api/users`：返回 `List<UserVO>`（密码已 `@JsonIgnore` 屏蔽）。
- 管理员守卫：service 层手写 `requireAdmin()`，非 ADMIN 抛 `BizException(FORBIDDEN)`（与 AgentService/ModelService 一致，不另起鉴权机制）。
- MVP 先做建+列；改角色/禁用/删号/改密后续补。

**5. 菜单与路由**
- 菜单定义用种子数据初始化（**不做**"菜单管理后台界面"）。
- 前端「用户管理」页经 `role_menu` 映射仅对 ADMIN 显示，成为菜单轴第一个真实落地。

**6. 与现有代码衔接**
- `listBases`/`listAgents` 后端**必须加 visibility + resource_access 过滤**（堵"列表返回全部"已知缺口）。
- 写操作继续用 service 层 `requireAdmin()`/`isAdmin()` 兜底（@PreAuthorize 实际未生效，靠手写）。

---

## 3. 代码组织规范

### 3.1 仓库顶层

```
hify/
├── backend/          # Spring Boot 单体（Maven），com.hify.hify
├── frontend/         # Vue 3 前端（Vite）
├── deploy/           # K8s 清单（backend/mysql/redis/pgvector/ingress/configmap/secret）
├── docker-compose.yml# 本地起 mysql/redis/pgvector
└── README.md
```

### 3.2 后端包结构（Modulith，按功能分包）

```
com.hify.hify
├── SayAgentApplication.java
├── common/            # 共享内核，禁止反向依赖业务包
│   ├── config/        # CorsConfig, AsyncConfig(线程池), SwaggerConfig, RedisConfig, JpaConfig
│   ├── security/      # SecurityConfig, JwtUtil, AuthFilter, CustomUserDetailsService
│   ├── exception/     # GlobalExceptionHandler, BizException
│   ├── base/          # BaseEntity(id/createdAt/updatedAt), BaseRepository
│   ├── tool/          # 统一工具契约(M8/T1)：Tool/ToolDefinition/ToolResult/ToolCall + BuiltinToolRegistry + builtin/(current-time)；跨 mcp/skill/conversation 共享，禁止业务包反向依赖
│   └── util/
├── modelprovider/     # 模型提供商：Controller/Service/Repository/Entity + client/(ProviderClient 统一接口 + OpenAi/Claude/Gemini/Ollama Client) + ResilienceDecorator + ProviderRouter
├── agent/             # Agent 配置：Controller/Service/Repository + Agent(entity: 名称/prompt/providerRef/knowledgeRefs/toolRefs)
├── conversation/      # 对话引擎(SSE)：ConversationController(返回 SseEmitter) / ConversationService / Conversation / Message
│   └── tool/          # 对话编排循环(M8/T3)：ToolLoopRunner(思考→执行→反思) + ToolRegistry(Agent toolRefs→工具列表) + ToolStepSink(进度出口)
├── knowledge/         # 知识库 RAG：KnowledgeService(上传/切分/向量化) / Document / DocumentChunk / embedding/EmbeddingService / retriever/RetrievalPort
├── workflow/          # 简版工作流：WorkflowEngine(顺序节点执行器) + node/(LLMNode/RetrievalNode/ToolNode/EndNode)
└── mcp/               # MCP 工具：McpClientManager(连接 Server/发现 tools/执行调用) + McpServer
```

**模块间解耦纪律（硬约束）**：
- 每个功能包自包含（自己的 controller/service/repo/entity/dto）。
- **分层职责边界**：`controller`（web 入口，仅做参数校验与响应封装）→ `service`（业务逻辑）→ `repository`（数据访问）；**禁止 `controller` 直接调用 `repository`/Mapper，必须经 `service`**。
- **跨模块调用只能走对方发布的 API 接口**（如 `XxxService`/`XxxApi`），**禁止直接 import 其他模块的 `entity`/`repository`/`impl` 等内部类**；若出现循环依赖，视为错误，必须立即重构（抽公共接口到 `common` 或事件解耦）。
- 示例（Agent 模块调用模型模块，只依赖接口不依赖实现）：
  ```java
  // modelprovider 模块发布接口（放在对外可见位置）
  public interface ModelService {
      ModelView getById(Long id);
  }
  // agent 模块仅 @Autowired ModelService，不引用 modelprovider 的 Entity/Repository
  @Service
  public class AgentService {
      private final ModelService modelService;   // 跨模块只依赖接口
      public AgentView build(Long modelId) { return modelService.getById(modelId); }
  }
  ```
- `common` 禁止反向依赖任何业务包。

### 3.3 前端结构（Vue 3 + Vite）

```
frontend/src
├── main.js / App.vue
├── router/            # /chat /agents /knowledge /models /workflows /mcp /login
├── stores/            # Pinia: auth, app
├── api/               # axios 封装: agent.js / knowledge.js / chat.js ...
├── views/             # Login / Chat(SSE) / AgentList / AgentEdit / Knowledge* / ModelProvider / Workflow / McpServer
├── components/        # 复用 UI（消息气泡、Markdown 渲染、表单）
└── utils/             # 请求、token 存储、SSE 解析
```

### 3.4 关键依赖

- **后端**：`spring-boot-starter-web` / `-validation` / `-actuator` / `-security`；`jjwt`；`spring-boot-starter-data-jpa` + `mysql-connector-j` + `postgresql` + `spring-boot-starter-data-redis` + `flyway-core`(+`-database-postgresql`)；`langchain4j-*`（core/open-ai/anthropic/google-ai-gemini/ollama/mcp/pgvector）；`resilience4j-spring-boot3`(+circuitbreaker/retry/bulkhead/timelimiter)；`lombok`；`springdoc-openapi-starter-webmvc-ui`；`spring-boot-starter-test`。
- **前端**：`vue@3` / `vue-router@4` / `pinia@2` / `axios` / `element-plus` / `markdown-it` / `@vueuse/core`；dev: `vite@5`。
- **版本纪律**：Spring Boot 用 3.3.x 父 POM 管理；Java 21（当前系统安装版本；Spring Boot 3.3.5 官方支持 21，虚拟线程已正式可用）；LangChain4j 迭代快，**先固定一个 1.x 稳定版再开发**。

### 3.5 API 响应契约（所有模块统一）

- **统一响应体**：所有 REST 接口返回 `{ "code": 0, "data": ..., "message": "ok" }`；`code=0` 成功，非 0 为业务错误（见 `ErrorCode`）。
- **错误码**：统一定义在 `common/exception/ErrorCode` 枚举（如 `MODEL_NOT_FOUND(1001)`），禁止散落魔法数字；全局异常由 `GlobalExceptionHandler` 翻译成上述结构。
- **异常与错误码命名唯一性（禁止臆造类）**：业务异常**统一用 `BizException`**（`common/exception/BizException`，继承 RuntimeException），错误码**统一用 `ErrorCode` 枚举**；**严禁自造 `AppException` / `BizCode` 等本仓库不存在的类**。任何任务拆分/编码在引用异常或错误码前，必须先 `search_content` 仓库确认其真实存在（对照 `common/exception/`），禁止凭通用脚手架习惯臆造类名（详见「子模块任务拆分」skill 的符号落地原则与「子模块任务拆分验收」skill 的臆造符号检测）。
- **分页结构**：列表接口返回 `{ "items": [...], "nextCursor": "上页末 id 或 null", "hasMore": true }`，**不使用页码/offset**（与 §6.4 keyset 分页一致）。
- **敏感字段**：响应 DTO 不得含密码/秘钥/token（用 `@JsonIgnore` 或专用 VO 过滤，见 §7.11）。

### 3.6 前端设计语言与页面开发流程（F 模块硬性约定）

> 登录页（`src/views/Login.vue`）已通过**花叔设计技能（huashu-design）**确立「Field.io 式流体沉浸」风格，作为**全站统一视觉基准**。后续页面（chat / agents / knowledge / models 等）必须沿用同一套设计令牌，保持视觉一致。

**设计令牌（design tokens，所有前端页面共用）**：

| 角色 | 取值 | 说明 |
|---|---|---|
| 背景底 | `#14110F`（暖近黑） | **禁止** `#0D1117` 深蓝底、**禁止**赛博霓虹 |
| 主强调色 A | `#5EEAD4`（青） | 聚焦态边框、粒子 |
| 主强调色 B | `#FFB454`（琥珀） | 粒子、渐变另一端 |
| 主按钮 | `linear-gradient(100deg, #5EEAD4, #FFB454)`，深色文字 `#14110F` | 沉浸科技感 |
| 错误/警示 | `#FF7A6B`（内联红字） | 不依赖 toast，错误就地提示 |
| 卡片 | 玻璃拟态：`backdrop-filter: blur(18px)` + 半透明描边 + 柔和投影 | 浮于粒子背景之上 |
| 输入框 | 深色半透明 `rgba(0,0,0,0.28)` | **必须**覆盖 `:-webkit-autofill` 白色填充：`box-shadow: 0 0 0 1000px rgba(0,0,0,0.28) inset`；根节点 `color-scheme: dark` |
| 字体 | UI/展示：Inter / PingFang SC / Microsoft YaHei；等宽（按需）：IBM Plex Mono | — |

**开发流程（硬规则）**：**每次开发新前端页面（`src/views/*`），必须先调用 `huashu-design` 设计技能**，走「需求理解 → 推荐 3 套差异化方向 → 可视化 Demo → 用户选方向 → 实现」流程；实现时**必须沿用上方设计令牌**，禁止未经设计技能直接堆样式、禁止私自换主色/换气质。实际令牌与基准实现见 `frontend/DESIGN.md`。

---

## 3.7 单文件规模与模块化硬约束（防「巨型文件」难维护）

> 用户 2026-08-04 明确要求：开发必须模块化，禁止单文件代码量过大导致难维护。本条为硬约束，未来所有 T* / 新功能开发必须遵守；代码审核（任务代码审核 skill）按此判定为缺陷。

- **单一职责优先**：一个文件只解决一类问题；跨职责的内容必须拆到对应模块/子文件，不得因「方便」堆在同一文件。
- **单文件规模软上限 400 行、硬上限 600 行**（含模板/脚本/样式；注释与空行计入）：
  - 超过 400 行即应主动评估拆分；超过 600 行**必须拆**，不允许「先堆着以后再说」。
  - 衡量以「逻辑块」为单位：一个 `.vue` 的 `<script setup>` 超过 400 行、或一个后端 `Service` 超过 500 行，即触发拆分。
- **前端拆分规则（Vue 3）**：
  - 视图 `views/*.vue` 只做「页面装配」：布局 + 子组件组合 + 路由逻辑；**具体交互/数据逻辑抽成 `composables/` 或放进 `stores/`**；
  - 重复的 UI 块（消息气泡、表单、弹窗、Markdown 渲染）抽成 `components/`，**不要在一个 .vue 里复制粘贴大段模板**；
  - Pinia store 按领域拆分（`auth` / `chat` / `agent` / `knowledge` ...），单 store ≤ 300 行；跨 store 共享逻辑抽到 `stores/` 下的 composable 或 `utils/`；
  - 工具函数按功能拆 `utils/request.js`、`utils/sse.js` 等，单文件单一职责。
- **后端拆分规则（Java / Modulith）**：
  - 类严格单一职责；一个 Service 既管「业务编排」又塞「大量转换/校验/第三方适配」时，抽 `XxxConverter` / `XxxValidator` / `XxxManager` / 子 Service；
  - Controller 只做参数校验 + 响应封装，禁止把业务逻辑写进 Controller（见 §3.2 分层纪律）；
  - 一个包内的「工具方法云集」抽成 `*.util` / `*.helper` 类，不要堆在 Service 里。
- **拆分判据速查**：当发现「改一个小需求要在 800 行文件里上下翻找」「一个文件 import 了十几个内部模块」「同一个文件里两种毫不相关的功能」——就是该拆的信号。
- **验收**：超过硬上限（600 行）的文件在代码审核视为缺陷；新功能若预估会超上限，**先拆结构再写代码**（不要先写成大文件再返工）。
- **关联**：§3.2（后端分层/分包）、§3.3（前端结构）、§10（子任务交付）、坑位库 K3（前端滚动重灾区示例）。

---

## 4. LLM 调用规范

> 依据 `04_外部LLM调用技术方案.md`。LLM 调用慢且不稳定，必须全程治理。

### 4.1 调用管线顺序（任一环失败即 Fallback，绝不卡死请求线程）

```
请求 → [限流 RateLimiter] → [舱壁 Bulkhead/独立线程池] → [熔断 CircuitBreaker]
     → [重试 Retry+退避] → [超时 TimeLimiter] → 实际调提供商
失败/熔断 → [降级 Fallback 切备用提供商]
```

### 4.2 线程管理

- **首选 Java 21 虚拟线程**：`spring.threads.virtual.enabled=true`，写普通阻塞代码即可高并发（Java 21 已正式支持虚拟线程）。
- **Java 21 备选（关闭虚拟线程时）**：专用 `llmExecutor` 线程池（显式 `ThreadPoolExecutor`，有界队列 `queueCapacity=200`，拒绝策略降级），并拆 **LLM生成 / Embedding / RAG检索** 三个池互不阻塞（防头阻塞）。
- 底层 HTTP 用 **WebClient（非阻塞）**；LangChain4j 支持自定义非阻塞客户端。
- **`CompletableFuture` 必须显式指定线程池**：❌ `CompletableFuture.supplyAsync(task)`（默认用 `ForkJoinPool.commonPool`，所有异步任务抢同一池）；✅ `CompletableFuture.supplyAsync(task, llmExecutor)`，确保 LLM 调用落在专用池。
- **禁止在数据库事务内做耗时 LLM/HTTP 调用**，先提交事务再异步调（编码规范规则 20）。

### 4.3 超时（两级都必须设）

- **HTTP 层**：按提供商定（Ollama 本地快、云端长）：`openai{connect-ms:3000,read-ms:60000}`、`claude{3000,90000}`、`gemini{3000,90000}`、`ollama{1000,30000}`。
- **应用层 SLA**：`TimeLimiter` 给"完整调用（含重试）"设上限，超时即中断走 Fallback；流式设"两次 chunk 最大间隔"，超过断流提示降级。

### 4.4 重试（Resilience4j Retry / Spring Retry）

- 指数退避 + 抖动：`waitDuration=1s, maxAttempts=3`。
- **只重试可恢复错误**：429/502/503/504/超时/连接异常；**400/401/403 永不重试**。
- 尊重 429 的 `Retry-After`；重试受 TimeLimiter 约束（剩余时间不够不再重试）；生成类 POST 用幂等键避免重复计费/写。

### 4.5 容错（Resilience4j 四件套 + Fallback）

1. **熔断**：每提供商一个 `CircuitBreaker` 实例（`failureRateThreshold:50, waitDurationInOpenState:30s`）。
2. **舱壁**：独立线程池隔离各提供商。
3. **降级**：按 Agent 优先级链 `OpenAI → Claude → Gemini → Ollama` 自动切备用。
4. **限流**：RateLimiter 保护配额防 429 也保护自身容量。
5. 优雅降级：全部不可用返回缓存/部分结果/"服务暂时降级"提示，而非 500。
6. 健康探测：定时轻量 ping 各提供商，辅助熔断恢复。

### 4.6 SSE 流式响应（用 Spring MVC 原生 `SseEmitter`，不引入 WebFlux）

- 控制器返回 `SseEmitter(120_000L)` 后**立即释放 Tomcat 线程**，token 推送到专用线程池执行。
- `SseEmitter` 超时须 ≥ `TimeLimiter` 预算，否则提前断流。
- 必须处理 `onCompletion`/`onTimeout`/`onError`，**客户端断开时取消后台 LLM 调用**（避免白烧 token / 线程泄漏）。
- 前端：GET 用 `EventSource`；需 POST 复杂 body 用 `fetch` + `ReadableStream`。

### 4.7 组件设计（贴在 modelprovider 包）

- `ProviderClient` 统一接口 `chat()/stream()/embed()/health()`。
- `ResilienceDecorator` 包 CircuitBreaker+Retry+TimeLimiter+Bulkhead。
- `ProviderRouter` 读 Agent 配置 + 各 CB 状态选主/备；配置全在 `application.yml`，加新提供商零改代码。

### 4.8 HTTP 客户端配置模板（OkHttp，LLM 调用专用）

```java
@Bean
public OkHttpClient standardLlmClient() {
    return new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(3))      // 对应各提供商 connect-ms
        .readTimeout(Duration.ofSeconds(60))         // 对应 read-ms（Ollama 调小）
        .connectionPool(new ConnectionPool(20, 5, TimeUnit.MINUTES)) // 最大空闲连接/保活
        .retryOnConnectionFailure(true)
        .build();
}
// 流式客户端可复用上述，仅 readTimeout 放宽到 120s
```
- 连接池大小按并发估算（50 人峰值 ~20 并发，20 足够）；**禁止每次调用 new OkHttpClient**（共享单例）。

### 4.9 调用可观测性（每次 LLM 调用必打 INFO 日志）

- 每次调用（含降级）必须打印 INFO 日志，字段固定：`provider`、`model`、`耗时ms`、`token消耗(入/出)`、`是否命中降级(fallback)`、`是否成功`。
  ```java
  log.info("llm.call provider={} model={} costMs={} inTok={} outTok={} fallback={} ok={}",
           provider, model, costMs, inTok, outTok, fallback, ok);
  ```
- 失败调用额外打 `ERROR` 并保留异常堆栈；据此可做成本与质量分析。

---

## 5. 部署架构

> 依据 `05_部署架构设计.md`。目标 50 人内部，生产 Docker + K8s。

### 5.1 组件清单

| 组件 | 形态 | 职责 |
|---|---|---|
| Nginx(Ingress) | K8s Ingress | HTTPS 终结、路由 `/api/*`→后端 `/`→前端、放宽 SSE 超时 |
| Vue 前端 | 静态构建物 | 配置页 + 聊天 UI，纯静态 |
| SayAgent 后端 | Deployment 无状态 2 副本 | 全部业务能力 + 调外部 LLM + Resilience4j |
| MySQL | StatefulSet/RDS | 业务主库（用户/Agent/模型/对话/知识元数据/工作流） |
| Redis | StatefulSet | 缓存配置、限流计数、SSE 会话/发布订阅 |
| pgvector(PostgreSQL) | 与 MySQL 并存 | 向量存储（文档 embedding、相似检索） |
| 对象存储/本地盘 | PV/S3 | 知识库原始文档 |
| ConfigMap/Secret | K8s | 数据库密码、LLM Key 等，不入代码 |

### 5.2 关键配置点

- **副本**：后端 2 副本（高可用 + 滚动发布零中断）；对话无状态可水平扩容。
- **SSE**：Nginx `proxy_buffering off; proxy_read_timeout 300s; proxy_send_timeout 300s;`（连续生成长文本 120s 可能不够，统一 300s 更安全）；可按需加 `limit-rps 20` 限制单 IP 请求速率防滥用。
- **优雅停机**：`server.shutdown=graceful` + K8s `terminationGracePeriodSeconds` 留足，让在飞 LLM 调用完成。
- **K8s 探针必须区分**：就绪探针 `/actuator/health/readiness`（Redis/DB 等依赖就绪才接流量）+ 存活探针 `/actuator/health/liveness`（进程存活即重启）；两者分开，避免滚动更新时把流量打到依赖未就绪的 Pod。
- **出网**：后端 Pod 需访问公网 LLM API（或公司代理）；Ollama 本地部署则放集群内。
- **持久化**：MySQL/PostgreSQL/Redis 用 PVC；文档用 PV/对象存储。
- **资源**：后端设 `requests/limits`（如 1C2G 起步），防 LLM 积压吃光内存。

### 5.3 请求链路

- **管理配置（低频）**：浏览器 → Nginx → Vue → 后端 → MySQL（存配置）→ Redis（缓存）。
- **对话（高频流式）**：浏览器 → Nginx → Vue → `/api/chat/stream` → 后端：Redis 取配置 → 拼上下文 → 查 pgvector 检索 → ProviderRouter 调 LLM(Resilience) → SseEmitter 逐 token 回传 → 写 MySQL/Redis。
- **MCP 调用**：对话引擎 → mcp → 内部 MCP Server → 工具结果拼回 prompt 继续生成。

---

## 6. 数据库规范

> 依据 `09_数据库性能规范.md`。**重要事实**：业务关系数据在 MySQL，向量在 pgvector（PostgreSQL 扩展），两者分开。本规范针对 MySQL 业务表。

### 6.1 通用字段约定（所有表套用）

```sql
CREATE TABLE `example` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记 0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='示例表';
```

- `id` 用 **BIGINT 自增**，不用 UUID 主键（防索引碎片）。
- `deleted` **软删除**：查询加 `WHERE deleted=0`，不真删数据。
- **实现纪律（硬性）**：软删「查询过滤 + 删除转 UPDATE」必须在每个含 `deleted` 的 `@Entity` 类上**显式**声明 `@SQLRestriction("deleted = 0")` 与 `@SQLDelete(sql = "UPDATE <表> SET deleted = 1 WHERE id = ?")`。**不可**依赖 `BaseEntity`（`@MappedSuperclass`）自动实现——实测 `@MappedSuperclass` 上的软删注解**不会传播**到子类查询（代码审核坑位5）。表名为 MySQL 保留字（如 `user`）时，`@SQLDelete` 的 SQL 表名须加反引号（`` `user` ``）。每张含 `deleted` 的实体都要逐条核对"注解是否在 @Entity 上、实跑查询 SQL 是否含 `deleted = 0`"。
- 统一 `utf8mb4` + `InnoDB`。

### 6.2 索引设计原则（建表逐条核对）

1. 主键自增 BIGINT，避免 UUID 主键。
2. 所有外键列（`user_id`/`agent_id` 等）**必须建索引**。
3. 高频 WHERE/ORDER BY 建索引；多条件用联合索引，**高区分度列放最前（最左前缀）**。
3b. **含 `deleted` 的查询必须把 `deleted` 纳入联合索引**（通常放第二列：区分度低但 `WHERE deleted=0` 极普遍），如 `(user_id, deleted, created_at)`；不可让 `deleted` 游离在索引外导致全表扫。
4. **单表索引 ≤ 5 个（含主键）**，写多读少表 ≤ 3 个（防写放大）。
5. 长字符串用前缀索引 `INDEX(name(50))`。
6. 低区分度枚举列不单独建索引，放联合索引末尾。
7. 不在 TEXT/MEDIUMTEXT 建普通索引（语义检索交 pgvector/全文索引）。

### 6.3 大表预判与应对

- **`message`** 预判百万级/年（每轮对话 1~2 行），重点防范。
- **分区**：`message` 按 `created_at` 做 RANGE 分区（主键须含分区键 `PRIMARY KEY(id, created_at)`）。
- **归档**：超 1 年消息搬 `message_archive` 冷表；热表只留近期。
- **不实时 COUNT(*) 大表**：用汇总表/异步更新或只显示"是否有更多"。
- **内容不索引**：`content` 用 MEDIUMTEXT；语义检索交 pgvector。
- **容量预警**：监控 `message` 行数，近百万评估分表/清理。

### 6.4 分页（一律 keyset，禁深 LIMIT offset）

```sql
-- 错误：LIMIT 100000, 20（offset 越大越慢，全表扫）
-- 正确：前端传 last_id，不是页码
SELECT * FROM message
WHERE conversation_id = ? AND deleted = 0 AND id < :last_id
ORDER BY id DESC LIMIT 20;
```

- 永远带 `ORDER BY` + 索引列过滤，命中 `idx_conv_created`。
- 列表页不显示精确总数，用"还有更多"近似。

### 6.5 AI 建表可执行模板（先读这段再写 DDL）

InnoDB + utf8mb4 + utf8mb4_0900_ai_ci；必有四字段 `id`(BIGINT 自增)/`created_at`/`updated_at`/`deleted`(软删)；外键列建索引；联合索引高区分度在前、且含 `deleted`；**单表索引总数 ≤ 5 个（含主键），写多读少表 ≤ 3 个**；预估大表用分区+归档；分页一律 keyset；向量/语义检索不放 MySQL，交 pgvector。

### 6.6 向量索引（pgvector 侧）

> **首版默认全表精确扫描，暂缓建 HNSW**（2026-07-27 拍板，与 §10 性能表 O2 一致）：内部 20~50 人、单库 chunk 数远 < 1 万，精确扫描已毫秒级；HNSW 是近似索引、本质拿召回率换速度，过早建反降"准"（小库更应"宁可慢一点也要准"）。**唯一触发条件**：单库 chunk 数达**数万级**且检索 P95 不可接受时，再建 HNSW（或 ivfflat 省内存）。
> 注：当前 `V2__knowledge_chunk.sql` 已建 HNSW 索引，属与决策冲突的遗留；需补 `V3__drop_chunk_hnsw.sql` 移除（见 `plans/K/` RAG 增强模块），首版检索走精确扫描。

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE knowledge_chunk (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  doc_id BIGINT, content TEXT,
  embedding vector(1536)   -- 维度随 embedding 模型定（hify 实际使用 1024，见 knowledge 包）
);
-- 首版不建向量索引（全表精确扫描，最准）；下方为"升级路径"，触发条件满足时再启用
-- CREATE INDEX idx_chunk_vec ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
-- 备选（更省内存）ivfflat：lists = sqrt(总行数)，行数<10万取 100；查询前需 SET ivfflat.probes = 10~20
-- CREATE INDEX idx_chunk_vec ON knowledge_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

### 6.7 索引监控策略（防退化三件套）

1. **开发期**：接 `p6spy` 拦截 SQL，对慢查询 `EXPLAIN` 检查是否 `type=ALL`（全表扫描）。
2. **CI 期**：关键查询写 `IndexCoverageTest`，断言执行计划非 `ALL`；出现全表扫描则阻断合并。
3. **生产期**：定期查 `performance_schema.table_io_waits_summary_by_index_usage`，找出长期未使用（或无索引）的 SQL 并补索引/改写。

> 索引会因后续需求变更而失效，这套监控保证 §6.2 规范持续有效。

---

## 7. 编码规范（基于阿里巴巴 Java 开发手册）

> AI 生成/修改 Java 代码**必须严格遵守**，违反即视为错误。覆盖：命名 / 常量 / 格式 / OOP / 集合 / 并发 / 控制语句 / 注释 / 异常 / 日志 / 单元测试 / 安全。每条都能直接执行。

### 7.1 命名（规则 1–6）

1. **类名** `UpperCamelCase`：抽象类 `Abstract*`/`Base*`，异常 `*.Exception`，测试 `*Test`；**方法/变量** `lowerCamelCase`。
2. **常量** `UPPER_SNAKE_CASE`，不以下划线或 `$` 开头结尾；**禁止魔法值**，字面量统一定义为常量。
3. **包名** 全小写、单数、见名知意（如 `com.hify.agent`），禁止语义不明缩写。
4. **分层命名**：接口 `XxxService` + 实现 `XxxServiceImpl`；数据访问 `XxxMapper`/`XxxDao`；入口 `XxxController`；模型后缀 `XxxDO`/`XxxDTO`/`XxxVO`/`XxxQuery`。
5. **布尔变量不用 `is` 前缀**（如 `deleted` 而非 `isDeleted`），避免序列化坑；POJO 同样遵守。
6. **集合声明** 用 `List<String> list`，不用 `String list[]`；变量名体现复数（`userIds`）。

### 7.2 常量与代码格式（规则 7–9）

7. **魔法值零容忍**：✅ `private static final int MAX_RETRY = 3;` 并用 `MAX_RETRY`；❌ `if (status == 3)`。
8. **单行不超过 120 字符**：超长在运算符、点号 `.` 处换行（符号落新行行首）；方法参数逗号后加空格。
9. **缩进 4 空格**，禁 Tab 与空格混用；类/方法之间空一行；`{}` 不可省略（见 7.8）。

### 7.3 异常处理（规则 10–14）

10. **禁止吞异常**：catch 不可空块或只 `printStackTrace()`；必须处理/记录/重抛。
11. **捕获具体异常**，不写 `catch (Exception e)` 一把抓；多 catch 从具体到宽泛。
12. **异常不做流程控制**：正常分支用 `if/else`，异常仅用于真正异常（网络/空指针）。
13. **资源在 finally / try-with-resources 释放**；锁/连接/流必须关闭，禁止泄漏。
14. **自定义异常继承 `RuntimeException`**，命名 `*Exception` 带 `message`；基础设施层异常统一转业务异常（`BizException`），不直接抛 `SQLException` 到 Controller。
14b. **前置防御 NPE**：方法返回值优先返回空集合 `Collections.emptyList()` 而非 `null`；接口入参用 `@NonNull`/`@NotBlank`/`@NotNull` 声明约束，不靠运行时判空。

### 7.4 日志（规则 15–19）

15. **用 SLF4J 门面 + Logback**（或 `@Slf4j`），禁止直接依赖具体实现。
16. **不用 `System.out/err.println`**；禁止循环体内打日志。
17. **占位符不拼接**：✅ `log.info("user {} login", uid)`；❌ `"user "+uid+" login"`。异常保留堆栈 `log.error("fail", e)`。
18. **级别纪律**：生产用 `INFO/WARN/ERROR`；`DEBUG` 仅本地，禁止生产大量打。
19. **禁止打印敏感信息**（密码/token/秘钥/身份证等）；外部入参日志脱敏。

### 7.5 并发（规则 20–23）

20. **线程池必须显式创建**：❌ 禁 `Executors.newFixedThreadPool()`（无界队列易 OOM）；✅ `new ThreadPoolExecutor(core, max, keepAlive, unit, queue, factory, rejectPolicy)`。
21. **线程不安全对象隔离**：`SimpleDateFormat`/`DecimalFormat` 用 `ThreadLocal` 或改用不可变 `DateTimeFormatter`；**`ThreadLocal` 用后必须在 `finally` 中 `remove()`，防线程池场景数据串号泄漏**。
22. **共享状态用并发容器**（`ConcurrentHashMap`）/原子类（`Atomic*`）；`synchronized` 范围尽量小；双重检查锁必须 `volatile`。
23. **异步/定时任务要兜底**：任务内异常必须捕获降级；跨线程传递 `traceId`/MDC 用包装器；**禁止在数据库事务内做耗时远程调用**（LLM/HTTP），先提交事务再异步调。

### 7.6 OOP 规约（规则 24–26）

24. **覆写方法必须加 `@Override`**；接口/抽象类变更时编译器能及时发现。
25. **equals 比较防 NPE**：✅ `"OPENAI".equals(type)`（常量在前）；❌ `type.equals("OPENAI")`（type 为 null 即 NPE）。枚举比较用 `==` 而非 `equals`。
26. **POJO 类不依赖业务构造顺序**；需序列化时实现 `Serializable` 并声明 `serialVersionUID`；`toString()` 用 Lombok `@Data`/`@ToString` 生成。

### 7.7 集合处理（规则 27–29）

27. **集合转数组用 `toArray(new T[0])`**；❌ 勿用 `toArray(new T[size])` 旧写法。数组转 List 用 `Arrays.asList(...)` 后**不可 add/remove**（抛 `UnsupportedOperationException`）。
28. **foreach 循环内禁止对元素 remove/add**：✅ 用 `Iterator.remove()` 或 `list.removeIf(x -> ...)`；❌ `for (x : list) { if (...) list.remove(x); }`（并发修改异常）。
29. **初始化集合指定容量**：`new ArrayList<>(16)`、`new HashMap<>(expectedSize * 4 / 3 + 1)`，避免频繁扩容。

### 7.8 控制语句（规则 30–31）

30. **if/else/for/while/do 必须用 `{}`**，即使单行；✅ `if (ok) { return; }`。
31. **禁止在 if 条件里赋值**（如 `if ((x = get()) != null)`）；switch 必须有 `default`；三目运算符两侧类型需一致，避免拆装箱隐患。

### 7.9 注释规约（规则 32–33）

32. **类与公共方法必须有 Javadoc**（`/** ... */`），说明职责、参数、返回值、抛出的异常；内部实现用行注释解释"为什么"。
33. **注释讲"为什么"而非"做什么"**（代码应自解释）；`TODO`/`FIXME` 须带作者与日期，定期清理。

### 7.10 单元测试（规则 34–35）

34. **核心业务逻辑必须单测覆盖**：用 JUnit 5，方法命名 `test方法_场景_预期`；用例间不依赖执行顺序；AI 生成业务代码时同步生成对应测试。
35. **测试不依赖真实外部资源**（DB/LLM/网络），用 Mockito 等 Mock；断言用 `Assertions`，❌ 不用 `System.out` 肉眼验结果。
    - **例外（切片/集成测试）**：`@DataJpaTest` 这类只验 JPA 一层的**切片集成测试**，允许连真实库（`@AutoConfigureTestDatabase(replace = NONE)`），前提是事务内执行、结束自动回滚、不污染业务数据；纯单元测试（Service/Util 逻辑）仍须 Mockito Mock，禁止连真库。

### 7.11 安全规约（规则 36–38）

36. **用户输入必须校验**（Bean Validation：`@NotNull`/`@Size`/`@Pattern`），防 SQL 注入/XSS；SQL 一律预编译参数绑定（`JpaRepository`/命名参数），❌ 字符串拼接 SQL。
37. **敏感数据不硬编码、不落日志、不返前端**：密码/秘钥/token 走配置中心或 K8s Secret 注入；配置文件秘钥用占位符 `${}` 从环境变量取；**API 响应中敏感字段必须用 `@JsonIgnore` 或专用 VO/DTO 过滤，禁止把含秘钥的 Entity 直接序列化返回**。
38. **权限校验在服务层做**（不只前端拦截）；重要操作（删 Agent、改模型、MCP 调用）记录审计日志。

---

## 8. 性能瓶颈优先级（一期处理清单）

> 依据 `07_性能瓶颈分析.md`。用户延迟几乎全被外部 LLM 主导，按严重程度排序。

| 排名 | 瓶颈 | 一期是否处理 | 做法 |
|---|---|---|---|
| 1 | **索引缺失导致的全表扫描** | **P0（与上线同步）** | 落实 §6.2 索引原则 + §6.7 索引监控三件套；所有查询 `EXPLAIN` 非 ALL 才过关 |
| 2 | **外部 LLM 延迟/限流/不稳定** | **必须** | 落实 `04` Resilience 管线（超时/重试/熔断/降级/限流）+ 流式 SSE 体感更快 |
| 3 | **并发慢调用耗尽线程/连接** | **需要** | SseEmitter + 专用 llmExecutor（或虚拟线程）；HikariCP 20–50；对话记录**异步写库** |
| 4 | **Nginx/SSE 缓冲与超时配置不当** | **需要（改配置）** | `proxy_buffering off; proxy_read_timeout/send_timeout 300s;` |
| 5 | pgvector 检索变慢 | 基本不需要 | 量小，建好 HNSW 索引即可 |
| 6 | MySQL 写入/连接压力 | 轻量处理 | 连接池 + Redis 缓存配置 + 异步落库 |
| 7 | Redis 单点 | 不需要 | 单实例 + AOF 持久化够用 |
| 8 | 文档批量 embedding 耗时 | 不需要 | 异步队列，不进对话热路径 |

**一期重点（务必落实的三件事）**：
1. **LLM 调用治理**（超时/重试/熔断/降级/限流）——体验与安全核心。
2. **线程与 SSE 配置**（专用线程池或虚拟线程 + Nginx 流式配置）——防堵死。
3. **异步落库 + 配置缓存**（轻量）——别让 DB 拖慢对话。

其余（pgvector 调优、Redis 高可用、embedding 并行）在 50 人规模下**不必动**；架构已用"向量库与业务库分离 + 无状态单体可扩容"提前规避。

---

## 9. 开发者协作约定（给 AI 助手）

- 开发者暂不熟悉 Java，涉及 Java 术语**必须先翻成大白话（比喻）再讲技术**。
- 每次交付固定带验收标签：**【类型】文档/代码/图｜【解决】哪个问题｜【状态】定稿/草稿/讨论稿｜【验证】怎么验证**。
- 默认风格：每篇先一句话结论+比喻；一次只讲一个点；优先给能跑的最简代码边做边学。
- 严格遵守本文件所有规范；新建业务表前先读 §6，写 Java 代码前先读 §7，调 LLM 前先读 §4。
- **数据库表结构变更一律走 Flyway**：所有 DDL 写成 `src/main/resources/db/migration/V{序号}__{描述}.sql`（如 `V20260720__add_model_provider.sql`），由 Flyway 在启动时自动执行；**AI 禁止生成 `ALTER`/`CREATE TABLE` 等直接连库执行的语句或建议手动改表**，改动必须落在迁移脚本里。
- **构建/命令输出的中文乱码规范（硬规则）**：
  - *根因*：Windows 控制台默认代码页是 GBK(936)，而 JDK/Maven 输出是 UTF-8，二者不一致 → 中文提示乱码。**乱码 ≠ 编译错误**，只要看到 `BUILD SUCCESS` 即通过，不要被乱码误导。
  - *预防*：在 Windows 跑 `mvn` 前先切到 UTF-8 控制台——`cmd` 执行 `chcp 65001`，PowerShell 执行 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`（或设 `$env:MAVEN_OPTS="-Dfile.encoding=UTF-8"`）。
  - *抑制特定提示*：`pom.xml` 已对 `maven-compiler-plugin` 加 `<compilerArgs><arg>-Xlint:-options</arg></compilerArgs>`，用于屏蔽 Lombok 注解处理器那条中文 note（它在 GBK 控制台下必乱码）。**新增编译参数时优先用此方式抑制已知中文噪音**。
  - *AI 行为*：遇到用户贴出乱码构建输出，先判断是否有 `BUILD SUCCESS/FAILURE`；只报乱码、无 FAILURE 时，明确告知「乱码无害，构建通过」，并引导按上面规范切 UTF-8 重跑以看清晰输出。

- **坑位库（按需加载，不在此展开）**：所有踩坑与解决方案统一沉淀在 `rule/坑位库.md`，**本文件只保留索引**（见 **§11**）。遇到对应报错/场景时，按 §11 指针去读该文档的具体解法。当前已沉淀：① Windows 中文路径下 `mvn spring-boot:run` → `ClassNotFoundException`（本地启动一律用 `java -jar`）；② PowerShell 中 `java -Dxxx=yyy -jar` 的 `-D` 参数被吞掉（加 `--%` 或改用 cmd）。

## 11. 坑位库索引（按需加载，细节见 `rule/坑位库.md`）

> 本索引只列「触发关键词 / 一句话结论 / 何时去读」，不展开细节。遇到下列报错或场景时，才打开对应文档章节。

| 序号 | 触发关键词 / 场景 | 一句话结论 | 去读 |
|---|---|---|---|
| K1 | `ClassNotFoundException: com.hify.hify.SayAgentApplication` 且工程路径含中文、正在用 `mvn spring-boot:run` | 子 JVM `sun.jnu.encoding=GBK` 解错中文 `-cp`；改用 `java -jar` | `rule/坑位库.md#k1-windows-中文路径下-mvn-spring-bootrun-失败` |
| K2 | PowerShell 执行 `java -Dfile.encoding=UTF-8 -jar ...` 报「找不到或无法加载主类 .encoding=UTF-8」 | `-D` 参数被 PowerShell 解析器吞掉；加 `--%`、用 `& java '-D...'` 或改用 cmd | `rule/坑位库.md#k2-powershell-中-java--dxxx-yyy--jar-的--d-参数被吞掉` |
| K3 | 发消息后新消息在滚动区外、需手动拖滚动条才能看到最新；`scrollTop=scrollHeight` 设了无效 | flex 滚动容器缺 `min-height:0`（overflow 不生效）；加 `min-height:0` + ResizeObserver 跟随 + 底部守卫 | `rule/坑位库.md#k3-前端-flex-滚动容器缺-min-height0--不自动跟随--overflow-不生效` |
| K4 | 登录后多个页面接口全部 `timeout of 15000ms exceeded`、对话/历史不加载 | 多个后端进程抢同一端口、一个「监听不服务」把请求挂到超时；先 `curl` 直连后端端口确认、再杀重复进程单实例启动 | `rule/坑位库.md#k4-后端重复实例抢同一端口--前端整页-15s-超时-端口监听但不服务` |

## 10. 子任务（T*）开发交付流程（硬规则，每个子模块通用）

**🔒 多任务并行防遗漏闸门（强制，先于三步交付执行）**：并行推进多个 T* 时，AI 极易因上下文切换遗漏规范。每次开发**任一** T*，**必须**先打开 `plans/防遗漏核对清单.md` 并严格执行其「二、三时机纪律」——即：①开始前读「三件套」(AGENTS.md 对应章节 + 该 T* 的「文件清单 / 交付物清单」+「验收点」)；②切换任务前把状态锁进 `00_总体进度表.md`(进行中 🔵 / 完成 ✅ / 阻塞 ⛔,**半成品禁私自标 ✅**)；③完成前逐条打勾「三、高频遗漏速查」(DDL 索引 / 软删注解 / 测试命名 / VO 秘钥隔离 / 跨模块只依赖接口 / 批量写文件后确认非空等)。**未过高频清单 + 未过验收点 + 未经用户本人确认,不得宣布该 T* 完成。** 该清单是 §10 的强制检查表,等同本文件硬规则;每次交付前还要走 `子模块任务拆分验收` + `任务代码审核` 两个 skill 当质检闸门。

**每开发一个子任务 T\*，AI 必须严格按以下三步交付，缺一不可：**

0. **(前置闸门)过防遗漏核对清单**：先开 `plans/防遗漏核对清单.md`,按上条执行「三时机纪律」+「高频遗漏速查」,否则不得进入下面三步。

1. **按清单文件清单生成代码**：只读 `plans/M*/T*` 文档里「文件清单 / 交付物清单」列出的文件来生成，**不多写、不漏写**；文件名、路径、包名、版本号一律照抄清单，不得擅自发挥。生成后简要列出「已生成文件 ↔ 清单条目」对照。
2. **按清单验收点给逐步验证**：生成完毕后，**逐条**对照清单里的「验收点」说明怎么验证，顺序与清单一致；每条都要指明「验证什么 / 在哪验证」。
3. **给可复制命令 + 明确通过标志**：每个验证动作都给**能直接复制粘贴**的命令（PowerShell/cmd 分开标注），并明确写出「看到什么输出算通过」；涉及 `BUILD SUCCESS/FAILURE`、`EXIT=0`、特定文件存在等可判定标志。**不要只说"跑一下看看"，必须给具体命令与预期输出。**

- **判定权归用户本人**：AI 给出验证命令与通过标志后，**不得自行宣布 T\* 通过**；必须由用户本人核对输出并明确回复「通过」（或等价确认），AI 才能把 `00_总体进度表.md` 对应行改为 ✅、并把指针移到下一 T\*。
- **环境前置**：涉及 Maven/Java 的验证，先确认 `JAVA_HOME` 与 `mvn -version` 指向的 JDK 版本与 `pom.xml` 的 `java.version` 一致；Windows 控制台中文乱码按 §9 规范处理（乱码 ≠ 失败）。

*本 AGENTS.md 汇总 `01`~`10` 全部要点，是 AI 编码与开发者协作的单一事实源。*

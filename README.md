# Hify

> 一句话定位：**给团队自己用的 AI 员工制造厂** —— 一人搭建平台，团队内 20–50 人都能拥有"懂公司文档 + 能操作公司系统"的 AI 助手。
>
> 技术栈：后端 **Spring Boot 3.3（Java 21）** + 前端 **Vue 3**；AI 用 **LangChain4j**；数据层 **MySQL 8（业务）+ PostgreSQL + pgvector（向量）+ Redis（缓存/限流）**。

---

## 这个项目是什么

Hify 是一个**单租户、自托管**的轻量 AI 平台，定位与 Dify / FastGPT 等大型开源平台不同：它不追求多租户与插件市场，而是聚焦"一个团队、自己部署、自己掌控数据与模型"。对 Java 技术栈的团队尤其友好——整套后端是标准 Spring Boot 单体（按功能分包的 Modulith），无需另学一套 Python/TS 生态。

核心链路：**登录 → 配模型 → 建 Agent → 挂知识库/工具 → 对话/API → 留日志 → 本地部署 → MCP 接内部系统**。

## 工程亮点（为什么值得一看）

- **LLM 调用全链路治理**：超时 / 重试（指数退避）/ 熔断 / 舱壁隔离 / 限流 / 自动降级（OpenAI → Claude → Gemini → Ollama），基于 Resilience4j，慢且不稳定的 LLM 调用不会拖垮请求线程。
- **SSE 流式对话**：原生 `SseEmitter` + 专用虚拟线程推送池，客户端断开即取消后台 LLM 调用，避免空烧 token。
- **RAG 知识库（基础版已落地，增强版规划中）**：上传 → 切片 → embedding → pgvector 检索 → 拼上下文问答；增强模块规划了混合检索（向量 + PG 全文）+ RRF 融合 + 阈值拒答 + 来源溯源。
- **严谨的工程纪律**：所有编码 / 数据库 / LLM / 部署规则收口在唯一的 [`AGENTS.md`](AGENTS.md)；每条 DDL 走 Flyway 迁移；软删除 + keyset 分页 + 索引纪律内置；敏感字段用 VO 隔离。
- **MCP 集成**：管理员配 MCP Server 地址，Agent 自动发现工具并调用内部系统，调用失败优雅降级。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3（Java 21）、Spring Security + JWT、Spring Data JPA、Flyway |
| AI | LangChain4j（OpenAI / Claude / Gemini / Ollama）、MCP |
| 前端 | Vue 3 + Vite + Pinia + Element Plus |
| 数据 | MySQL 8（业务）、PostgreSQL + pgvector（向量）、Redis（缓存/限流） |
| 部署 | Docker Compose / Kubernetes（含 K8s 清单） |

## 当前状态（如实标注）

> 后端为主，前端与 RAG 增强仍在推进中。

| 模块 | 状态 | 说明 |
|---|---|---|
| M1 基础设施与共享内核 | ✅ | 骨架 / docker-compose / 统一响应 / Flyway |
| M2 登录与轻量权限 | ✅ | Spring Security + JWT（BCrypt） |
| M3 模型管理与 LLM 治理 | ✅ | 多厂商 Client + Resilience 管线 + 降级链 |
| M4 Agent 配置 | ✅ | 表单式配置 + 跨模块接口解耦 |
| M5 知识库与 RAG（基础版） | ✅ | 上传/切片/embedding/余弦检索 |
| M6 对话引擎 + 聊天 UI | 🔵 | SSE 后端已完成，聊天 UI 待浏览器实测 |
| M7 MCP 集成 + 单租户部署 | 🔵 | MCP 调用/降级 + Docker/K8s 清单已落地 |
| F 前端页面 | 🔧 | 9 个 Vue 页面代码已落地，待浏览器实测联通 |
| K RAG 增强模块 | ⬜→🔵 | K1 已落地，K2 待验收，K3–K11 规划中（混合检索/拒答/溯源/异步索引） |

详细里程碑见 [`plans/00_总体进度表.md`](plans/00_总体进度表.md)（内部开发进度，未随仓库发布）。

## 本地跑通

### 前置要求

- **JDK 21**
- **Maven 3.8+**
- **Docker + Docker Compose**（起 MySQL / Redis / pgvector 三个中间件）

### 第 1 步：起中间件

```powershell
docker compose up -d
docker compose ps   # 三容器均 healthy 即过关
```

### 第 2 步：启动后端

> Windows 中文工程路径注意：`mvn spring-boot:run` 在含中文路径下会 fork 子 JVM 并以 GBK 解码 `-cp` 导致 `ClassNotFoundException`；**推荐用 `java -jar` 启动**（fat jar 内部 classpath 无中文路径）。详见 `rule/坑位库.md` 的 K1。

```powershell
cd backend
mvn -DskipTests package
java -Dfile.encoding=UTF-8 -jar target\hify-backend-0.0.1-SNAPSHOT.jar
```

看到 `Started HifyApplication` 即启动成功。健康检查：

```powershell
curl http://localhost:9095/actuator/health
```

接口文档：<http://localhost:9095/swagger-ui.html>

## 安全说明（重要）

`backend/src/main/resources/application.yml` 中带有**仅供本地开发**的弱默认值：

- 管理员默认账号/口令：`admin` / `admin123`
- 开发用 JWT 密钥：`dev-only-secret-key-please-change-in-prod-32bytes!!`

**任何对外部署务必用环境变量覆盖**，切勿使用默认值：

```powershell
$env:HIFY_ADMIN_PASSWORD="你的强口令"
$env:HIFY_JWT_SECRET="至少32字节的随机密钥"
```

## 目录结构

```
hify/
├── backend/          # Spring Boot 单体（Maven），com.hify.hify
├── frontend/         # Vue 3 前端（Vite）
├── deploy/           # 部署相关（initdb / K8s 清单）
├── doc/              # 对外设计文档（需求 / 选型 / 部署 / 架构 / 数据库规范）
├── docker-compose.yml# 本地起 mysql/redis/pgvector
├── rule/             # 开发坑位库（踩坑与解法）
├── AGENTS.md         # 项目规则唯一入口（编码/数据库/LLM/部署规范）
└── README.md
```

## 文档

`doc/` 下是完整的对外设计文档：`01_功能需求文档` ~ `09_数据库性能规范`，记录"为什么这么定"的背景与结论。

## License

[MIT](LICENSE)

# SayAgent 快速开始（Quickstart）

> 目标：让别人**克隆仓库后，用最少的前置依赖把整套系统跑起来**。
> 两种路线任选其一。推荐**路线 A（Docker 一键）**，本机只需装 Docker，不需要 JDK / Maven / Node。

---

## 路线 A：Docker 一键起（推荐，零本地工具链）

### 前置
- 安装 [Docker Desktop](https://www.docker.com/products/docker-desktop/)（Windows / macOS）或 Docker Engine + docker compose 插件（Linux）。
- 无需安装 JDK21 / Maven / Node——后端编译、前端打包都在容器内完成。

### 步骤
```bash
# 1) 克隆
git clone <你的仓库地址> sayagent && cd sayagent

# 2) 一键启动（macOS / Linux / Git Bash）
./start.sh
#    Windows 用户双击 start.bat，或在 PowerShell / CMD 里跑 start.bat

# 它做的事：
#   - 若 deploy/.env 不存在，自动从 deploy/.env.example 复制（默认本地演示凭据）
#   - docker compose up -d --build（构建 backend / frontend 镜像 + 起 MySQL/Redis/pgvector）
#   - 轮询后端健康检查，就绪后打印访问地址
```

### 访问
- 前端界面：**http://localhost:8080**
- 后端接口文档（Swagger）：**http://localhost:9095/swagger-ui.html**
- 默认管理员：`admin` / `admin123`（来自 `deploy/.env` 的 `SAYAGENT_ADMIN_PASSWORD`）

### 停止
```bash
./stop.sh        # Windows: stop.bat
# 仅停容器、保留数据卷；要连数据一起清：docker compose -f deploy/docker-compose.yml down -v
```

### 首次会慢，正常
第一次 `up --build` 会拉取 `maven:3.9-eclipse-temurin-21`、`node:20-alpine`、`mysql:8.0`、`pgvector/pgvector:pg16`、`nginx:alpine` 等镜像，并在容器内下载 Maven / npm 依赖，通常需要 **3~8 分钟**。之后有镜像与依赖缓存，重建会快很多。

---

## 路线 B：本机开发运行（需要完整工具链）

适合要改源码、不想走 Docker 构建的开发者。

### 前置
- **JDK 21**
- **Maven 3.8+**（注意：Windows 若仓库在**中文路径**下，`mvn` 会因 fork 子 JVM 用 GBK 解码 `-cp` 而 `ClassNotFoundException`；建议把仓库放到纯英文路径，或用路线 A 的 Docker 构建）
- **Node 20+** 与 npm
- **Docker + Docker Compose**（仅用于起 MySQL / Redis / pgvector 三个中间件）

### 步骤
```bash
# 1) 起中间件（MySQL / Redis / pgvector）
docker compose up -d
docker compose ps          # 三容器均 healthy 即过关

# 2) 后端
cd backend
mvn -DskipTests package
java -Dfile.encoding=UTF-8 -jar target/sayagent-backend-0.0.1-SNAPSHOT.jar
#   看到 Started SayAgentApplication 即成功；健康检查 curl http://localhost:9095/actuator/health

# 3) 前端（另开一个终端）
cd frontend
npm install
npm run dev                # 开发服务器，默认 http://localhost:6177
#   或构建静态产物：npm run build → 用任意静态服务器托管 frontend/dist
```
> 前端开发服务器已配置 `/api` 反代到 `http://localhost:9095`（见 `frontend/vite.config.js`，端口 6177），
> 且与后端 CORS 白名单（`application.yml` 的 `sayagent.cors.allowed-origins`）一致。

---

## 安全提示（重要）

`deploy/.env.example` 与后端配置里带有**仅供本地开发**的弱默认值：
- 管理员默认账号/口令：`admin` / `admin123`
- 开发用 JWT 密钥：`dev-only-secret-key-please-change-in-prod-32bytes!!`

**任何对外部署务必修改 `deploy/.env`**：把 `SAYAGENT_ADMIN_PASSWORD` 改成强口令、`SAYAGENT_JWT_SECRET` 改成至少 32 字节的随机串，并修改 MySQL / PostgreSQL 密码。

---

## 排错

| 现象 | 排查 |
|------|------|
| `docker compose up` 报找不到 jar / dist | 旧版需本机先构建；现版已改为容器内构建。**确认用的是最新 `deploy/Dockerfile` 与 `deploy/docker-compose.yml`**，并带 `--build` 参数。 |
| 前端页面空白 / 404 | 确认走的是路线 A（镜像内置 dist），而非旧版挂载空 `frontend/dist`。`docker compose -f deploy/docker-compose.yml logs frontend` 看 nginx 是否起来。 |
| 后端一直不健康 | `docker compose -f deploy/docker-compose.yml logs backend`；常见是中间件没起来或 Flyway 迁移失败（看 MySQL 连接 / 表结构）。 |
| Windows 中文路径下 `mvn` 报错 | 改走路线 A（Docker 构建在 Linux 容器内，无此问题），或把仓库移到纯英文路径。 |
| 改了密码不生效 | 确保改的是 `deploy/.env` 且重启时带 `--build` 或至少 `up -d`（env 在 `up` 时注入）；已启动的容器需 `down` 后再 `up`。 |

---

## 已知问题（部署前必读）

### 1. 端口冲突
`deploy/docker-compose.yml` 默认占用宿主机端口：`3306`(MySQL) / `5432`(pgvector) / `6379`(Redis) / `9095`(backend) / `8080`(frontend)。
若本机已运行同类服务（如本机独立 MySQL、其它项目的容器、或正在调试的旧 backend 实例），`docker compose up -d` 会报 `Bind for 0.0.0.0:xxxx failed: port is already allocated` 而整体失败。
**解决**：停掉占用端口的进程/容器后重跑；或临时修改 `deploy/docker-compose.yml` 里对应的 `ports` 映射（如 `"3307:3306"`）。注意 frontend 反代与后端互联走容器网络，只改宿主机映射端口不影响内部通信。

### 2. 测试代码与主代码签名不同步（不影响运行）
本仓库测试代码存在 11 处与主代码构造函数 / record 签名不同步（集中在 `agent`、`conversation`、`mcp`、`modelprovider` 包），例如各 Client 构造函数已从单 `OkHttpClient` 改为 `(OkHttpClient, @Qualifier("streamOkHttpClient") OkHttpClient)` 双参数，而对应测试仍用旧签名。
因此**直接 `mvn package` 会因测试编译失败而中断**。
**这不影响系统运行**：生产镜像构建已在 `deploy/Dockerfile` 用 `mvn -Dmaven.test.skip=true package` 跳过测试编译（fat jar 不含测试，属标准做法）。用 `start.sh` / `start.bat` 构建出的镜像可正常启动。
测试代码的修复是独立任务，建议单独一轮对齐主代码签名后提交，不在「可运行交付」范围内。

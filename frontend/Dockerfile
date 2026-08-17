# SayAgent 前端镜像（多阶段构建）
# 依据：AGENTS.md §3.1 / §5.2(SSE 反代)、T4_部署Dockerfile与docker_compose与K8s清单.md
#
# 关键改进（相对旧版）：旧版 docker-compose 直接挂载 `../frontend/dist` 到 nginx，
#   但 dist/ 被 .gitignore 忽略，别人 clone 下来是空的 → nginx 挂空站点。
#   现在用多阶段：阶段1 在容器内 `npm ci && build` 产出 dist，阶段2 nginx 托管。
#   - 用户机器只需装 Docker，无需 Node / npm。
#   - SSE 友好的 nginx 配置由 deploy/nginx.conf 提供（从仓库根 build context 拷贝）。
#
# 用法：由 docker compose 自动调用（build.context = 仓库根 `..`，dockerfile = frontend/Dockerfile）。
#   注意：COPY 路径相对于 build context（仓库根），所以写 `frontend/...` 与 `deploy/nginx.conf`。

# ============ 阶段1：node 构建静态产物 ============
FROM node:20-alpine AS build
WORKDIR /app

# 先拷依赖清单并安装，利用层缓存
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

# 再拷源码并构建
COPY frontend/ .
RUN npm run build

# ============ 阶段2：nginx 托管 ============
FROM nginx:alpine
# 前端静态资源
COPY --from=build /app/dist /usr/share/nginx/html
# SSE 友好的反代配置（/api → backend:9095）
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

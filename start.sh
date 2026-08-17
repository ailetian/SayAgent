#!/usr/bin/env bash
# SayAgent 一键启动脚本（macOS / Linux / Git Bash）
# 作用：自动生成 .env（若不存在）→ 构建并启动全栈 → 轮询后端健康检查 → 打印访问地址。
# 前置：只需安装 Docker（含 docker compose 插件）。无需本机 JDK / Maven / Node。
set -e

cd "$(dirname "$0")"

# 1) 环境变量
ENV_FILE=deploy/.env
if [ ! -f "$ENV_FILE" ]; then
  cp deploy/.env.example "$ENV_FILE"
  echo "[SayAgent] 已生成 deploy/.env（默认本地演示凭据，生产请修改密码 / JWT 秘钥）"
fi
# 让下面的 echo 能读到 admin 密码
set -a
. "$ENV_FILE"
set +a

echo "[SayAgent] 开始构建并启动（首次会拉取 maven/node 基础镜像并下载依赖，请耐心等待 3~8 分钟）..."
docker compose -f deploy/docker-compose.yml up -d --build

echo "[SayAgent] 等待后端就绪（最多 5 分钟）..."
READY=0
for i in $(seq 1 60); do
  if curl -fsS http://localhost:9095/actuator/health >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 5
done

echo ""
echo "=============================================="
if [ "$READY" -eq 1 ]; then
  echo " SayAgent 已启动 ✅"
else
  echo " SayAgent 后端未在预期时间内就绪，请排查："
  echo "   docker compose -f deploy/docker-compose.yml logs backend"
fi
echo "----------------------------------------------"
echo " 访问地址:   http://localhost:8080"
echo " 接口文档:   http://localhost:9095/swagger-ui.html"
echo " 管理员:     admin / ${SAYAGENT_ADMIN_PASSWORD:-admin123}"
echo " 停止命令:   ./stop.sh"
echo "=============================================="

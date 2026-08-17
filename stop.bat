@echo off
REM SayAgent 停止脚本（Windows）
REM 仅停止并移除容器，保留数据卷（MySQL/Redis/pgvector 数据不丢）。
cd /d "%~dp0"
docker compose -f deploy\docker-compose.yml down
echo [SayAgent] 已停止。数据卷保留；如需清空数据，执行: docker compose -f deploy\docker-compose.yml down -v
pause

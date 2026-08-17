@echo off
REM SayAgent 一键启动脚本（Windows）
REM 作用：自动生成 deploy\.env（若不存在）→ 构建并启动全栈 → 轮询后端健康检查 → 打印访问地址。
REM 前置：只需安装 Docker Desktop（含 docker compose）。无需本机 JDK / Maven / Node。
setlocal
cd /d "%~dp0"

REM 1) 环境变量
if not exist deploy\.env (
  copy deploy\.env.example deploy\.env >nul
  echo [SayAgent] 已生成 deploy\.env（默认本地演示凭据，生产请修改密码 / JWT 秘钥）
)

echo [SayAgent] 开始构建并启动（首次会拉取 maven/node 基础镜像并下载依赖，请耐心等待 3~8 分钟）...
docker compose -f deploy\docker-compose.yml up -d --build

echo [SayAgent] 等待后端就绪（最多 5 分钟）...
set /a n=0
:wait
curl -fsS http://localhost:9095/actuator/health >nul 2>&1
if %errorlevel%==0 (
  goto done
)
set /a n+=1
if %n% geq 60 (
  echo [SayAgent] 等待超时，请排查：docker compose -f deploy\docker-compose.yml logs backend
  goto finish
)
timeout /t 5 >nul
goto wait

:done
echo.
echo ==============================================
echo  SayAgent 已启动
echo ----------------------------------------------
echo  访问地址:   http://localhost:8080
echo  接口文档:   http://localhost:9095/swagger-ui.html
echo  管理员:     admin / 见 deploy\.env 的 SAYAGENT_ADMIN_PASSWORD
echo  停止命令:   stop.bat
echo ==============================================

:finish
endlocal
pause

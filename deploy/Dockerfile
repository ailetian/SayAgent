# Hify 后端镜像
# 依据：AGENTS.md §1(Java 21)、§3.1(deploy/ 仓库结构)、T4_部署Dockerfile与docker_compose与K8s清单.md
#
# 用法（在仓库根目录执行）：
#   1) 先产出 jar：  mvn -f backend/pom.xml package -DskipTests
#   2) 构建镜像：  docker build -f deploy/Dockerfile -t sayagent-backend .
#   3) 运行：      docker run -p 9095:9095 --env-file .env sayagent-backend
#
# 说明：
#   - 基础镜像 eclipse-temurin:21-jre（Spring Boot 3.3.5 官方支持 Java 21，虚拟线程已正式可用）。
#   - 仅拷可执行 jar，启动命令保持简单；敏感配置（DB 密码 / JWT 秘钥 / 管理员密码）一律由
#     环境变量注入（§7.11 / §9），镜像内无明文秘钥。
#   - 端口 9095 与 backend/src/main/resources/application.yml 的 server.port 保持一致；
#     不要在镜像/启动命令里写死 SERVER__PORT（会劫持 Spring server.port，见 M7 启动修复记要）。

FROM eclipse-temurin:21-jre

WORKDIR /app

# 拷可执行 jar（相对路径基于 build context = 仓库根目录 `.`）
COPY backend/target/sayagent-backend-0.0.1-SNAPSHOT.jar app.jar

# 允许通过 JAVA_OPTS 注入 JVM 参数（内存限制 / 时区等），不写死秘钥
ENV JAVA_OPTS="-Duser.timezone=UTC"

EXPOSE 9095

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

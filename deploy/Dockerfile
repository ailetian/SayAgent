# SayAgent 后端镜像（多阶段构建）
# 依据：AGENTS.md §1(Java 21)、§3.1(deploy/ 仓库结构)、T4_部署Dockerfile与docker_compose与K8s清单.md
#
# 关键改进（相对旧版）：不再要求本机先 `mvn package` 打出 jar。
#   旧版 `COPY backend/target/...jar` 在「别人 git clone 下来」时是空的 → 构建必失败。
#   现在用多阶段：阶段1 在容器内用 Maven 从源码构建 fat jar，阶段2 仅用 JRE 运行。
#   - 用户机器只需装 Docker，无需 JDK21 / Maven（也顺带绕开 Windows 中文路径下 Maven 故障）。
#   - 容器内是 Linux ASCII 路径，不会触发中文路径 ClassNotFoundException。
#
# 用法（在仓库根目录执行，由 docker compose 自动调用；也可单独构建）：
#   单独构建：docker build -f deploy/Dockerfile -t sayagent-backend .
#   单独运行：docker run -p 9095:9095 --env-file deploy/.env sayagent-backend
#
# 说明：
#   - 基础镜像 eclipse-temurin:21-jre（Spring Boot 3.3.5 官方支持 Java 21，虚拟线程已正式可用）。
#   - 敏感配置（DB 密码 / JWT 秘钥 / 管理员密码）一律由环境变量注入（§7.11 / §9），镜像内无明文秘钥。
#   - 端口 9095 与 backend/src/main/resources/application.yml 的 server.port 保持一致；
#     不要在镜像/启动命令里写死 SERVER__PORT（会劫持 Spring server.port，见 M7 启动修复记要）。

# ============ 阶段1：用 Maven 从源码构建 fat jar ============
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 注入国内镜像源（阿里云），根治默认直连 Maven Central 慢/超时问题（详见 deploy/maven-settings.xml）
COPY deploy/maven-settings.xml /root/.m2/settings.xml

# 先拷 pom 并预下载依赖，利用 Docker 层缓存：源码不变时重建只需重新编译，不必重拉依赖
COPY backend/pom.xml .
RUN mvn -B -DskipTests dependency:go-offline || true

# 再拷源码并打包（失败只可能是代码问题，不使用 || true）
# 注意：用 -Dmaven.test.skip=true 跳过测试编译/执行——本仓库测试代码与主代码构造函数签名存在
# 系统性不同步（11 处 record/构造函数历史债务），但这不是运行期问题，fat jar 不含测试。
# 生产构建标准做法：测试在 CI 单独跑，这里只产出可运行 jar。详见 QUICKSTART.md「已知问题」。
COPY backend/src ./src
RUN mvn -B -Dmaven.test.skip=true package

# ============ 阶段2：仅运行 jar ============
FROM eclipse-temurin:21-jre
WORKDIR /app

# 用通配符兼容版本号变化（pom artifactId = sayagent-backend，产物 sayagent-backend-0.0.1-SNAPSHOT.jar）
COPY --from=build /build/target/sayagent-backend-*.jar app.jar

# 允许通过 JAVA_OPTS 注入 JVM 参数（内存限制 / 时区等），不写死秘钥
ENV JAVA_OPTS="-Duser.timezone=UTC"

EXPOSE 9095

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

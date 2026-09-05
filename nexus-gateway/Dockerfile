# NexusChain nexus-gateway 多阶段生产 Dockerfile
# ============================================================================
# Phase 2 任务 #102：多阶段构建（Gradle + JDK 17 → distroless java17 nonroot）
#
# 构建上下文必须是 repo 根目录（gateway 依赖 project(':nexus-sdk:java')，
# 必须在根构建上下文中构建以解析 project() 引用）：
#   docker build -t nexus-gateway:1.9.7 -f nexus-gateway/Dockerfile .
#
# Stage 1 (builder): gradle:8.5-jdk17，编译产出 Spring Boot fat jar
# Stage 2 (runtime): gcr.io/distroless/java17-debian13:nonroot (UID=65532, 非 root)
#
# 镜像体积估算：distroless base ~180MB + fat jar ~50-70MB ≈ 230-250MB
#   注：含 SCA/Nacos/Sentinel/Seata/Feign/JPA/Redis/Flyway/Web3j 等重依赖，
#   严格 < 200MB 需启用 Spring Boot layered jar 或 jlink 自定义 JRE（见报告）。
# ============================================================================

# === Stage 1: builder ===
FROM gradle:8.14-jdk17 AS builder
USER root
WORKDIR /workspace

# 分层缓存优化：先 COPY Gradle wrapper 与根构建配置（变更频率低，命中缓存）
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle

# COPY 依赖模块源码（project() 引用 + composite build includeBuild）
# nexus-core / nexus-sdk / nexus-rpc-doc：根构建 include 子模块，project() 依赖
COPY nexus-rpc-doc ./nexus-rpc-doc
COPY nexus-core ./nexus-core
COPY nexus-sdk ./nexus-sdk
COPY nexus-common ./nexus-common
# composite build 模块（根 settings.gradle includeBuild）
COPY nexus-consortium ./nexus-consortium
COPY nexus-settlement ./nexus-settlement
COPY nexus-compliance ./nexus-compliance
COPY nexus-analytics ./nexus-analytics
COPY nexus-oracle ./nexus-oracle
COPY nexus-api-gateway ./nexus-api-gateway

# COPY 目标模块源码（变更频率高，放最后，最大化分层缓存命中）
COPY nexus-gateway ./nexus-gateway

# 构建 fat jar：根构建上下文解析 project(':nexus-sdk:java') 依赖
# 跳过测试与 jacoco 报告，加速构建
RUN ./gradlew :nexus-gateway:bootJar --no-daemon -x test -x jacocoTestReport

# 提取 fat jar（排除 *-plain.jar 普通 jar）并重命名为 app.jar
RUN find /workspace/nexus-gateway/build/libs -name '*.jar' ! -name '*-plain.jar' \
    -exec cp {} /workspace/app.jar \;

# === Stage 2: runtime (distroless nonroot) ===
# gcr.io/distroless/java17-debian13:nonroot 默认用户 nonroot (UID=65532)
# 无 shell / 无 curl / 无包管理器，攻击面最小，满足非 root 要求
FROM gcr.io/distroless/java17-debian13:nonroot
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar
# busybox 静态二进制（~1.2MB，无动态依赖）：仅用于 compose healthcheck（wget）。
# distroless 无 curl/wget/shell，注入单个静态 busybox 提供 wget 子命令，
# 不引入 shell 与包管理器，保持最小攻击面（DAST 修复 2026-08-28）。
COPY --from=busybox:1.36.1 /bin/busybox /busybox

# 暴露服务端口（与 application.yml server.port: 8080 对齐）
EXPOSE 8080

# HEALTHCHECK 说明：distroless nonroot 镜像不含 shell/curl，
# 通过注入的静态 busybox 提供 wget，healthcheck 在编排层配置：
#   - docker-compose: ['CMD', '/busybox', 'wget', '-q', '-O', '/dev/null', 'http://localhost:8080/actuator/health']
#   - Kubernetes: livenessProbe / readinessProbe (httpGet /actuator/health)
# 端点：GET http://localhost:8080/actuator/health

# JVM 参数：
#   -XX:+UseContainerSupport      自动识别容器 cgroup 内存限制
#   -XX:MaxRAMPercentage=75.0     堆上限 = 容器内存 × 75%（预留 25% 给非堆/Metaspace/直接内存）
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

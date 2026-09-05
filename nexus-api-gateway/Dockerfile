# NexusChain nexus-api-gateway 多阶段生产 Dockerfile
# ============================================================================
# P3-T2：Spring Cloud Gateway 统一 API 入口多阶段构建
#   （Gradle + JDK 17 → distroless java17 nonroot）
#
# 构建上下文必须是 repo 根目录（nexus-api-gateway 作为 composite build，
# 由根 settings.gradle 的 includeBuild 引入）：
#   docker build -t nexus-api-gateway:2.0.0 -f nexus-api-gateway/Dockerfile .
#
# Stage 1 (builder): gradle:8.5-jdk17，编译产出 Spring Boot fat jar
# Stage 2 (runtime): gcr.io/distroless/java17-debian13:nonroot (UID=65532, 非 root)
#
# 镜像体积估算：distroless base ~180MB + fat jar ~40-55MB ≈ 220-235MB
#   注：Spring Cloud Gateway 依赖较轻（WebFlux + Reactor Netty + SCA），
#   无 JPA / Flyway / gRPC 等重依赖，体积小于 nexus-gateway。
# ============================================================================

# === Stage 1: builder ===
FROM gradle:8.14-jdk17 AS builder
USER root
WORKDIR /workspace

# 分层缓存优化：先 COPY Gradle wrapper 与根构建配置（变更频率低，命中缓存）
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle

# COPY 依赖模块源码（composite build includeBuild）
# nexus-api-gateway 不依赖 project(':nexus-sdk:java') 等根构建子模块，
# 但根 settings.gradle 的 includeBuild 会扫描所有 includeBuild 路径，
# 因此需 COPY 全部 composite build 模块以使根构建上下文完整。
COPY nexus-rpc-doc ./nexus-rpc-doc
COPY nexus-core ./nexus-core
COPY nexus-sdk ./nexus-sdk
COPY nexus-consortium ./nexus-consortium
COPY nexus-settlement ./nexus-settlement
COPY nexus-compliance ./nexus-compliance
COPY nexus-analytics ./nexus-analytics
COPY nexus-oracle ./nexus-oracle

# COPY 目标模块源码（变更频率高，放最后，最大化分层缓存命中）
COPY nexus-api-gateway ./nexus-api-gateway

# 构建 fat jar：根构建上下文通过 includeBuild 解析 nexus-api-gateway
# 跳过测试与 jacoco 报告，加速构建
RUN ./gradlew :nexus-api-gateway:bootJar --no-daemon -x test -x jacocoTestReport

# 提取 fat jar（排除 *-plain.jar 普通 jar，api-gateway jar.archiveClassifier='plain'）
RUN find /workspace/nexus-api-gateway/build/libs -name '*.jar' ! -name '*-plain.jar' \
    -exec cp {} /workspace/app.jar \;

# === Stage 2: runtime (distroless nonroot) ===
# gcr.io/distroless/java17-debian13:nonroot 默认用户 nonroot (UID=65532)
# 无 shell / 无 curl / 无包管理器，攻击面最小，满足非 root 要求
FROM gcr.io/distroless/java17-debian13:nonroot
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar

# busybox 静态二进制（~1.2MB，无动态依赖）：仅用于 compose healthcheck（wget）。
# 与 nexus-gateway/nexus-core/nexus-bridge/nexus-wallet-service/nexus-signing-service
# 的 Dockerfile 保持同一模式；K8s 侧健康检查仍走 httpGet 探针，不依赖本二进制。
COPY --from=busybox:1.36.1 /bin/busybox /busybox

# 暴露服务端口（与 application.yml server.port: ${SERVER_PORT:8085} 对齐）
EXPOSE 8085

# HEALTHCHECK 说明：distroless nonroot 镜像不含 shell/curl，
# 无法在 Dockerfile 中配置 CMD 式 HEALTHCHECK。
# 健康检查在编排层配置（P0-8 审计修复：原 prod compose 用 curl 探测，
# 而 distroless 内无 curl，健康检查恒失败并阻塞 depends_on 链）：
#   - docker-compose: ['CMD', '/busybox', 'wget', '-q', '-O', '/dev/null', 'http://localhost:8085/actuator/health']
#   - Kubernetes: livenessProbe / readinessProbe (httpGet /actuator/health)
# 端点：GET http://localhost:8085/actuator/health

# JVM 参数：
#   -XX:+UseContainerSupport      自动识别容器 cgroup 内存限制
#   -XX:MaxRAMPercentage=75.0     堆上限 = 容器内存 × 75%（预留 25% 给非堆/Metaspace/直接内存）
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
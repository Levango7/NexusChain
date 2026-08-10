# NexusChain nexus-compliance 库模块 Dockerfile
# ============================================================================
# Phase 2 任务 #102：多阶段构建（Gradle + JDK 17 → distroless java17 nonroot）
#
# ⚠️ 重要：nexus-compliance 是库模块，不是可独立运行的 Spring Boot 服务。
#   - build.gradle 中 bootJar.enabled = false（不产出 fat jar）
#   - 无 @SpringBootApplication / 无 main class / 无 application.yml
#   - 作为 composite build (includeBuild) 被 nexus-gateway 消费
#
# 本 Dockerfile 产出包含 plain jar 的 artifact 镜像，用途：
#   1. 推送到制品库（CI 产出物归档）
#   2. 被其他镜像构建引用（COPY --from=nexus-compliance:1.9.7 /app/app.jar）
#   不作为服务运行（无 ENTRYPOINT / 无 EXPOSE）。
#
# 构建上下文必须是 repo 根目录：
#   docker build -t nexus-compliance:1.9.7 -f nexus-compliance/Dockerfile .
#
# 镜像体积估算：distroless base ~180MB + plain jar ~30-40KB ≈ ~180MB
# ============================================================================

# === Stage 1: builder ===
FROM gradle:8.5-jdk17 AS builder
USER root
WORKDIR /workspace

# 分层缓存优化：先 COPY Gradle wrapper 与根构建配置
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle

# COPY 依赖模块源码（composite build includeBuild 链）
COPY nexus-rpc-doc ./nexus-rpc-doc
COPY nexus-core ./nexus-core
COPY nexus-sdk ./nexus-sdk
COPY nexus-consortium ./nexus-consortium
COPY nexus-settlement ./nexus-settlement
COPY nexus-compliance ./nexus-compliance
COPY nexus-analytics ./nexus-analytics
COPY nexus-oracle ./nexus-oracle

# 构建 plain jar（bootJar disabled，jar.archiveClassifier=''）
# 跳过测试与 jacoco 报告，加速构建
RUN ./gradlew :nexus-compliance:jar --no-daemon -x test -x jacocoTestReport

# 提取 plain jar 并重命名为 app.jar
RUN find /workspace/nexus-compliance/build/libs -name '*.jar' \
    -exec cp {} /workspace/app.jar \;

# === Stage 2: artifact (distroless nonroot, 无 ENTRYPOINT) ===
# gcr.io/distroless/java17-debian12:nonroot 默认用户 nonroot (UID=65532)
# 无 shell / 无 curl / 无包管理器，满足非 root 要求
FROM gcr.io/distroless/java17-debian12:nonroot
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar

# 库模块：无服务端口、无 ENTRYPOINT，作为 jar artifact 容器。
# 如需提取 jar：docker create --name tmp nexus-compliance:1.9.7 && \
#               docker cp tmp:/app/app.jar ./ && docker rm tmp
# NexusChain nexus-core 多阶段生产 Dockerfile
# ============================================================================
# REQ-01 / T01：多阶段构建（Gradle + JDK 17 → distroless java17 nonroot）
#
# nexus-core 为嵌套 Gradle 结构（nexus-core/nexus-core/ 子模块，application 插件），
# 构建上下文为 ./nexus-core，独立 settings.gradle（rootProject.name = java-nexuscore），
# 不依赖根项目其他模块，可独立构建。
#   docker build -t nexus-core:2.1.0 ./nexus-core
#
# Stage 1 (builder): gradle:8.5-jdk17，编译产出 fat jar（:nexus-core:fatJar 任务）
# Stage 2 (runtime): gcr.io/distroless/java17-debian12:nonroot (UID=65532, 非 root)
#
# 镜像体积估算：distroless base ~180MB + fat jar ~80-100MB ≈ 260-280MB
#   注：nexus-core 含 netty/grpc/protobuf/web3j/bouncycastle/quartz 等重依赖。
# ============================================================================

# === Stage 1: builder ===
FROM gradle:8.5-jdk17 AS builder
USER root
WORKDIR /workspace

# 分层缓存优化：先 COPY Gradle wrapper 与根构建配置（变更频率低，命中缓存）
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle

# COPY 子模块源码（变更频率高，放最后，最大化分层缓存命中）
COPY nexus-core ./nexus-core

# 构建 fat jar：application 插件 + 自定义 fatJar 任务（archiveClassifier=all）
# 跳过测试与 jacoco 报告，加速构建
# TRAVIS_BRANCH=master 让 build.gradle 的 gitCurBranch() 不调用 git（gradle 镜像无 git）
RUN TRAVIS_BRANCH=master ./gradlew :nexus-core:fatJar --no-daemon -x test -x jacocoTestReport

# 提取 fat jar（*-all.jar）并重命名为 app.jar
RUN find /workspace/nexus-core/build/libs -name '*-all.jar' \
    -exec cp {} /workspace/app.jar \;

# === Stage 2: runtime (distroless nonroot) ===
# gcr.io/distroless/java17-debian12:nonroot 默认用户 nonroot (UID=65532)
# 无 shell / 无 curl / 无包管理器，攻击面最小，满足非 root 要求
FROM gcr.io/distroless/java17-debian12:nonroot
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar

# 暴露服务端口（与 application.properties server.port: 19585 对齐）
EXPOSE 19585

# HEALTHCHECK 说明：distroless nonroot 镜像不含 shell/curl，
# 无法在 Dockerfile 中配置 CMD 式 HEALTHCHECK。
# 健康检查请在编排层配置：
#   - Kubernetes: livenessProbe / readinessProbe (httpGet /height 或 /actuator/health)
#   - docker-compose: 需改用 distroless:debug 镜像或外部 probe
# 端点：GET http://localhost:19585/height（nexus-core 区块高度端点）

# JVM 参数：
#   -XX:+UseContainerSupport      自动识别容器 cgroup 内存限制
#   -XX:MaxRAMPercentage=75.0     堆上限 = 容器内存 × 75%（预留 25% 给非堆/Metaspace/直接内存）
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
# NexusChain nexus-signing-service 多阶段生产 Dockerfile
# ============================================================================
# Phase 2 任务 #102：多阶段构建（Gradle + JDK 17 → distroless java17 nonroot）
#
# 构建上下文必须是 repo 根目录（signing-service 依赖 project(':nexus-sdk:java')，
# 必须在根构建上下文中构建以解析 project() 引用）：
#   docker build -t nexus-signing-service:1.9.2 -f nexus-signing-service/Dockerfile .
#
# Stage 1 (builder): gradle:8.5-jdk17，编译产出 Spring Boot fat jar
# Stage 2 (runtime): gcr.io/distroless/java17-debian12:nonroot (UID=65532, 非 root)
#
# protobuf 特殊处理：
#   signing-service 应用 com.google.protobuf:0.9.4 插件，从 src/main/proto/*.proto
#   生成 Java gRPC stub 到 build/generated/source/proto/main/{java,grpc}。
#   protoc / protoc-gen-grpc-java 二进制由 protobuf-gradle-plugin 在构建期自动下载，
#   无需在 Dockerfile 中预装。COPY src 已包含 *.proto 源文件，构建时自动生成。
#
# 镜像体积估算：distroless base ~180MB + fat jar ~50-65MB ≈ 230-245MB
#   注：含 gRPC/protobuf/LevelDB/BouncyCastle/SCA 等依赖，
#   严格 < 200MB 需启用 layered jar 或 jlink 优化（见报告）。
# ============================================================================

# === Stage 1: builder ===
FROM gradle:8.5-jdk17 AS builder
USER root
WORKDIR /workspace

# 分层缓存优化：先 COPY Gradle wrapper 与根构建配置（变更频率低，命中缓存）
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle

# COPY 依赖模块源码（project() 引用 + composite build includeBuild）
COPY nexus-rpc-doc ./nexus-rpc-doc
COPY nexus-core ./nexus-core
COPY nexus-sdk ./nexus-sdk
COPY nexus-common ./nexus-common
COPY nexus-consortium ./nexus-consortium
COPY nexus-settlement ./nexus-settlement
COPY nexus-compliance ./nexus-compliance
COPY nexus-analytics ./nexus-analytics
COPY nexus-oracle ./nexus-oracle
COPY nexus-api-gateway ./nexus-api-gateway

# COPY 目标模块源码（含 src/main/proto/*.proto，protobuf 插件构建期自动生成 stub）
COPY nexus-signing-service ./nexus-signing-service

# 构建 fat jar：signing-service bootJar enabled (archiveClassifier='')
# protobuf-gradle-plugin 在 bootJar 之前自动执行 generateProto 任务，
# 下载 protoc:3.22.2 + protoc-gen-grpc-java:1.54.0 并生成 Java stub。
# 跳过测试与 jacoco 报告，加速构建
RUN ./gradlew :nexus-signing-service:bootJar --no-daemon -x test

# 提取 fat jar（排除 *-plain.jar 普通 jar，signing-service jar.archiveClassifier='plain'）
RUN find /workspace/nexus-signing-service/build/libs -name '*.jar' ! -name '*-plain.jar' \
    -exec cp {} /workspace/app.jar \;

# === Stage 2: runtime (distroless nonroot) ===
# gcr.io/distroless/java17-debian12:nonroot 默认用户 nonroot (UID=65532)
# 无 shell / 无 curl / 无包管理器，攻击面最小，满足非 root 要求
FROM gcr.io/distroless/java17-debian12:nonroot
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar

# 暴露服务端口（与 application.yml server.port: ${SERVER_PORT:8082} 对齐）
EXPOSE 8082

# HEALTHCHECK 说明：distroless nonroot 镜像不含 shell/curl，
# 无法在 Dockerfile 中配置 CMD 式 HEALTHCHECK。
# 健康检查请在编排层配置：
#   - Kubernetes: livenessProbe / readinessProbe (httpGet /actuator/health)
#   - docker-compose: 需改用 distroless:debug 镜像或外部 probe
# 端点：GET http://localhost:8082/actuator/health

# JVM 参数：
#   -XX:+UseContainerSupport      自动识别容器 cgroup 内存限制
#   -XX:MaxRAMPercentage=75.0     堆上限 = 容器内存 × 75%
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
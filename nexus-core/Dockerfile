# NexusChain nexus-core 多阶段生产 Dockerfile
# ============================================================================
# REQ-01 / T01：多阶段构建（Gradle + JDK 17 → distroless java17 nonroot）
#
# nexus-core 为嵌套 Gradle 结构（nexus-core/nexus-core/ 子模块，application 插件）。
# 构建上下文为 repo 根目录，使用根 settings.gradle（include ':nexus-core:nexus-core'），
# 嵌套项目路径为 :nexus-core:nexus-core，fatJar 任务路径为 :nexus-core:nexus-core:fatJar。
#   docker build -t nexus-core:2.1.0 -f nexus-core/Dockerfile .
#
# Stage 1 (builder): gradle:8.5-jdk17，编译产出 fat jar（:nexus-core:nexus-core:fatJar 任务）
# Stage 2 (runtime): gcr.io/distroless/java17-debian12:nonroot (UID=65532, 非 root)
#
# 镜像体积估算：distroless base ~180MB + fat jar ~80-100MB ≈ 260-280MB
#   注：nexus-core 含 netty/grpc/protobuf/web3j/bouncycastle/quartz 等重依赖。
# ============================================================================

# === Stage 1: builder ===
FROM gradle:8.14-jdk17 AS builder
USER root
WORKDIR /workspace

# 分层缓存优化：先 COPY 根 Gradle wrapper 与根构建配置（变更频率低，命中缓存）
# 构建上下文为 repo 根目录，COPY 路径相对于根
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle

# COPY 依赖模块源码（project() 引用 + composite build includeBuild）
# 根 settings.gradle include 了 nexus-sdk:java / nexus-common / includeBuild
# nexus-consortium 等模块，构建上下文必须包含这些目录，否则 Gradle 配置阶段
# 报 "Included build ... does not exist"（P4 Trivy Docker 修复：nexus-core 此前
# 遗漏补齐 COPY 列表，参照 nexus-gateway 等 8 个模块的 Dockerfile 对齐）
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
COPY nexus-bridge ./nexus-bridge
COPY nexus-signing-service ./nexus-signing-service
COPY nexus-wallet-service ./nexus-wallet-service

# 构建 fat jar：根 settings.gradle include ':nexus-core:nexus-core'，
# 故 fatJar 任务路径为 :nexus-core:nexus-core:fatJar（嵌套项目）。
# 跳过测试与 jacoco 报告，加速构建
# TRAVIS_BRANCH=master 让 build.gradle 的 gitCurBranch() 不调用 git（gradle 镜像无 git）
RUN TRAVIS_BRANCH=master ./gradlew :nexus-core:nexus-core:fatJar --no-daemon -x test -x jacocoTestReport

# 提取 fat jar（*-all.jar）并重命名为 app.jar
# 嵌套项目 build 目录：nexus-core/nexus-core/build/libs/
RUN find /workspace/nexus-core/nexus-core/build/libs -name '*-all.jar' \
    -exec cp {} /workspace/app.jar \;
# 剥离 module-info.class：依赖中的 module-info.class 会导致 JVM 将 fat jar
# 视为模块化 jar，限制只有导出包的类可访问（org.nexus.Start 不在导出包中，
# 报 ClassNotFoundException）。Gradle exclude '**/module-info.class' 可能
# 因 Ant 模式匹配差异未覆盖根级文件，此处用 zip -d 确保彻底移除。
RUN apt-get update && apt-get install -y --no-install-recommends zip
RUN zip -d /workspace/app.jar \
    'module-info.class' \
    'META-INF/versions/*/module-info.class' \
    ; echo "=== 验证 module-info.class 已移除 ===" && \
    (jar tf /workspace/app.jar | grep "module-info.class" || echo "All module-info.class removed!") && \
    echo "=== 验证 org/nexus/Start.class 存在 ===" && \
    (jar tf /workspace/app.jar | grep "org/nexus/Start.class" || echo "Start.class NOT FOUND!") && \
    echo "=== jar 文件大小 ===" && \
    ls -la /workspace/app.jar && \
    echo "=== MANIFEST.MF ===" && \
    unzip -p /workspace/app.jar META-INF/MANIFEST.MF | head -10 && \
    echo "=== javap 检查 Start.class ===" && \
    (cd /tmp && jar xf /workspace/app.jar org/nexus/Start.class && javap -p org/nexus/Start.class | head -5 || echo "javap FAILED")


# === Stage 1.5: directory builder ===
# distroless 无 shell/mkdir/chown，用 busybox 创建 nonroot 可写目录
FROM busybox:1.36.1 AS dir-builder
RUN mkdir -p /app/leveldb /app/data && \
    touch /app/leveldb/.keep /app/data/.keep && \
    chown -R 65532:65532 /app/leveldb /app/data

# === Stage 2: runtime (distroless nonroot) ===
# gcr.io/distroless/java17-debian12:nonroot 默认用户 nonroot (UID=65532)
# 无 shell / 无 curl / 无包管理器，攻击面最小，满足非 root 要求
FROM gcr.io/distroless/java17-debian12:nonroot
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar
# busybox 静态二进制（~1.2MB，无动态依赖）：仅用于 compose healthcheck（wget）。
# distroless 无 curl/wget/shell，注入单个静态 busybox 提供 wget 子命令，
# 不引入 shell 与包管理器，保持最小攻击面（DAST 修复 2026-08-28）。
COPY --from=busybox:1.36.1 /bin/busybox /busybox
# 创建 nonroot 用户可写目录：
# - /app/leveldb：LevelDB 缓存（nexus.cache-dir 为空时回退到 user.dir + "/leveldb"）
# - /app/data：链上状态快照持久化（nexus.chain.state-dir 默认 ./data/）
# distroless 无 shell/mkdir，通过 busybox 中间阶段创建并 chown 65532
COPY --chown=nonroot:nonroot --from=dir-builder /app/leveldb /app/leveldb
COPY --chown=nonroot:nonroot --from=dir-builder /app/data /app/data

# 暴露服务端口（与 application.properties server.port: 19585 对齐）
EXPOSE 19585

# HEALTHCHECK 说明：distroless nonroot 镜像不含 shell/curl，
# 通过注入的静态 busybox 提供 wget，healthcheck 在编排层配置：
#   - docker-compose: ['CMD', '/busybox', 'wget', '-q', '-O', '/dev/null', 'http://localhost:19585/height']
#   - Kubernetes: livenessProbe / readinessProbe (httpGet /height 或 /actuator/health)
# 端点：GET http://localhost:19585/height（nexus-core 区块高度端点）

# JVM 参数：
#   -XX:+UseContainerSupport      自动识别容器 cgroup 内存限制
#   -XX:MaxRAMPercentage=75.0     堆上限 = 容器内存 × 75%（预留 25% 给非堆/Metaspace/直接内存）
# 使用 -cp 而非 -jar：-jar 模式下 JarClassLoader 报 ClassNotFoundException
# （原因待查，可能与 Class-Path manifest 属性交互有关），-cp 使用 AppClassLoader
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-cp", "app.jar", "org.nexus.Start"]
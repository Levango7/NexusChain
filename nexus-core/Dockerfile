# NexusChain nexus-core 多阶段生产 Dockerfile
# ============================================================================
# REQ-01 / T01：多阶段构建（Gradle + JDK 17 → distroless java17 nonroot）
#
# nexus-core 为嵌套 Gradle 结构（nexus-core/nexus-core/ 子模块，application 插件）。
# 构建上下文为 repo 根目录，使用根 settings.gradle（include ':nexus-core:nexus-core'），
# 嵌套项目路径为 :nexus-core:nexus-core，jar 任务路径为 :nexus-core:nexus-core:jar。
#   docker build -t nexus-core:2.1.0 -f nexus-core/Dockerfile .
#
# Stage 1 (builder): gradle:8.14-jdk17，编译产出 thin jar + 依赖 lib/
# Stage 2 (runtime): gcr.io/distroless/java17-debian13:nonroot (UID=65532, 非 root)
#
# 采用 thin jar + Class-Path manifest 方案（替代 fat jar）：
#   - thin jar（~1MB）：仅含项目编译类 + META-INF/MANIFEST.MF（Class-Path: lib/*.jar）
#   - lib/（~80-100MB）：所有外部依赖 jar，由 copyDependencies 任务复制
#   - JVM 通过 -jar 读取 manifest 的 Class-Path 自动加载 lib/ 下的依赖
# 此方案避免了 fat jar 的 ZIP 中央目录膨胀问题（51256+ 条目导致 distroless JRE
# 的 JarClassLoader 无法定位 org.nexus.Start，尽管 unzip/jar tf/javap 均可读取）。
#
# 镜像体积估算：distroless base ~180MB + thin jar ~1MB + lib/ ~80-100MB ≈ 260-280MB
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

# 构建 thin jar + 复制依赖到 lib/
# jar 任务：打包项目类 + Class-Path manifest（引用 lib/*.jar）
# copyDependencies 任务：复制所有外部依赖 jar 到 build/libs/lib/
# 跳过测试与 jacoco 报告，加速构建
# TRAVIS_BRANCH=master 让 build.gradle 的 gitCurBranch() 不调用 git（gradle 镜像无 git）
RUN TRAVIS_BRANCH=master ./gradlew :nexus-core:nexus-core:jar :nexus-core:nexus-core:copyDependencies --no-daemon -x test -x jacocoTestReport

# 提取 thin jar（排除 fat jar *-all.jar）并重命名为 app.jar
# 嵌套项目 build 目录：nexus-core/nexus-core/build/libs/
RUN find /workspace/nexus-core/nexus-core/build/libs -maxdepth 1 -name '*.jar' ! -name '*-all.jar' \
    -exec cp {} /workspace/app.jar \;

# 复制依赖 lib/ 目录
RUN cp -r /workspace/nexus-core/nexus-core/build/libs/lib /workspace/lib

# 2026-09-21 DAST 修复：导出应用配置文件到独立目录
# ---------------------------------------------------------------------------
# 背景：nexus-core/build.gradle 的 jar 任务显式 `exclude 'application.properties'`
# 与 `exclude 'application-local.properties'`，原因是 core 被 wallet/signing 等
# 服务经 nexus-sdk:java 传递依赖为**库**时，jar 根上的 application.properties
# 会进入服务侧 Spring Boot 的 config data 加载链，用 core 的 server.port=19585
# 覆盖掉服务自身端口（Tomcat http-nio-19585 实证）。
#
# 但该修复的注释断言「core 独立运行形态（Dockerfile 源码全量构建）不受影响」
# **与事实不符** —— 本 Dockerfile 恰恰执行 `:nexus-core:nexus-core:jar`，
# 即容器使用的正是那个**不含任何配置**的 jar。后果（CI 实测）：
#   PlaceholderResolutionException: Could not resolve placeholder
#     'transaction.day.count'  at org.nexus.Start.main
# 容器内 nexus-core 启动即失败，DAST 门禁从未真正扫描过任何东西。
#
# 修法：jar 继续保持「库友好」（不把配置塞回 jar，避免重新引入端口覆盖），
# 改为在镜像内单独携带配置文件，运行时经 SPRING_CONFIG_ADDITIONAL_LOCATION
# 显式加载（见 docker-compose.yml 的 nexus-core 服务）。
# 只拷贝**被 git 跟踪**的配置文件，即：
#   application.properties / application-local.properties
#
# 2026-09-21 修正：初版把 application.yml 也列入了拷贝清单，导致 CI 构建
# 直接失败：`cp: cannot stat 'application.yml': No such file or directory`。
# 原因：.gitignore:162 有 `**/src/main/resources/application.yml`，属
# 「P0/密钥管理修复：保护生产配置」的一部分（防止含明文凭据的配置被提交），
# 该文件**从未进过 git**，因此 CI 的构建上下文里不存在它。
#
# 已本地实证：仅两个 properties 即可正常启动 ——
# 占位符错误 0 次，日志中 application.yml 出现 0 次，
# 应用顺利通过全部配置解析（仅在需要 Postgres 时停下，CI 由 compose 提供）。
# 故 application.yml 对启动并非必需，不得纳入镜像构建。
RUN mkdir -p /workspace/appconfig && \
    cd /workspace/nexus-core/nexus-core/src/main/resources && \
    cp application.properties application-local.properties /workspace/appconfig/ && \
    ls -l /workspace/appconfig/

# 诊断：验证 thin jar 和依赖
RUN echo "=== thin jar 检查 org/nexus/Start.class ===" && \
    (jar tf /workspace/app.jar | grep "org/nexus/Start.class" || echo "NOT FOUND IN THIN JAR") && \
    echo "=== MANIFEST.MF Main-Class ===" && \
    (unzip -p /workspace/app.jar META-INF/MANIFEST.MF | grep "Main-Class" || echo "NO Main-Class") && \
    echo "=== 依赖 jar 数量 ===" && \
    ls /workspace/lib/*.jar 2>/dev/null | wc -l && \
    echo "=== thin jar 大小 ===" && \
    ls -lh /workspace/app.jar && \
    echo "=== lib/ 总大小 ===" && \
    du -sh /workspace/lib


# === Stage 1.5: directory builder ===
# distroless 无 shell/mkdir/chown，用 busybox 创建 nonroot 可写目录
# /app/logs（2026-09-14 10b 补）：logback.xml:18 ./logs/nexuscore.log 无 profile
# 守卫，WORKDIR=/app → /app/logs；目录缺失即 Logback 启动失败（演练 CrashLoop 实证）
FROM busybox:1.36.1 AS dir-builder
RUN mkdir -p /app/leveldb /app/data /app/logs && \
    touch /app/leveldb/.keep /app/data/.keep /app/logs/.keep && \
    chown -R 65532:65532 /app/leveldb /app/data /app/logs

# === Stage 2: runtime (distroless nonroot) ===
# gcr.io/distroless/java17-debian13:nonroot 默认用户 nonroot (UID=65532)
# 无 shell / 无 curl / 无包管理器，攻击面最小，满足非 root 要求
FROM gcr.io/distroless/java17-debian13:nonroot
WORKDIR /app
# thin jar：仅含项目类 + Class-Path manifest
COPY --from=builder /workspace/app.jar app.jar
# 外部依赖：Class-Path manifest 中 lib/*.jar 相对路径，WORKDIR=/app 解析为 /app/lib/
COPY --from=builder /workspace/lib lib/
# 应用配置（2026-09-21 DAST 修复）：jar 内不含配置（见 builder 阶段说明），
# 单独携带并由 SPRING_CONFIG_ADDITIONAL_LOCATION 加载。
COPY --from=builder /workspace/appconfig config/
# busybox 静态二进制（~1.2MB，无动态依赖）：仅用于 compose healthcheck（wget）。
# distroless 无 curl/wget/shell，注入单个静态 busybox 提供 wget 子命令，
# 不引入 shell 与包管理器，保持最小攻击面（DAST 修复 2026-08-28）。
COPY --from=busybox:musl /bin/busybox /busybox
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
# java -jar app.jar：JVM 读取 manifest 的 Main-Class=org.nexus.Start 和
# Class-Path=lib/*.jar，自动加载主类和依赖。
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

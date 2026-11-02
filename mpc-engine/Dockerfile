# syntax=docker/dockerfile:1
# mpc-engine 构建镜像（审计报告 §4.1 方案 A）
# Rust 密码学引擎独立进程，gRPC 端口 50051。

# === 构建阶段 ===
# v2.2.0 CGGMP21 批（7895c03）：rust:1.85 → rust:1.88。cggmp21 0.6.3 依赖链
# 新引入的 darling 0.23 / serde_with 3.22 要求 rustc >= 1.88（Docker 构建实测
# 1.85.1 报 requires rustc 1.88.0）。1.88 为满足门槛的最低 minor 版本，
# 保持可复现钉版；后续升 cggmp24 时再评估随 stable 浮动。
FROM rust:1.88 AS builder

# tonic-build 生成 gRPC stub 需要 protoc；
# pkg-config + libssl-dev 为部分密码学库的可选系统依赖（按需启用）。
# v2.2.0 CGGMP21 批（7895c03）：cggmp21 0.6.3 依赖链锁定 rug → gmp-mpfr-sys
# （源码编译 GMP），构建容器需 GMP/MPFR 头文件与 m4/make/build-essential
# （gmp-mpfr-sys build.rs 驱动 ./configure && make）。本地 Windows 等价物为
# MSYS2 的 m4+make（项目记忆 nexuschain-build-environment 已录）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        protobuf-compiler \
        pkg-config \
        libssl-dev \
        libgmp-dev \
        libmpfr-dev \
        m4 \
        make \
        build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . .
# CI 构建上下文为 repo 根目录（Java 模块需根级 gradlew/build.gradle），
# Cargo.toml 在 mpc-engine/ 子目录，需切换后构建。
# --features tls：K8s 生产路径 MPC_REQUIRE_TLS=true fail-closed——无 tls
# feature 的二进制直接拒启（main.rs:301）。此前无 --features 构建的镜像
# 是"能构建不能跑"的缺陷镜像（kind 冒烟 5dce903 轮 CrashLoop 实证）。
WORKDIR /app/mpc-engine
RUN cargo build --release --features tls
# distroless 运行阶段没有 shell，也没有 RUN：挂载约定目录必须在本阶段造好再 COPY。
# 见运行阶段的权限注释。
RUN mkdir -p /app/runtime-dirs/app/data/sessions \
             /app/runtime-dirs/app/sessions \
             /app/runtime-dirs/home/mpc/sessions

# === 运行阶段 ===
# 2026-11-02：debian:trixie-slim → gcr.io/distroless/cc-debian13。
# 动机：trixie-slim 自带 apt / util-linux / perl / ncurses / libsqlite3-0 等整层
# 系统包，每轮 Trivy DB 更新都可能冒出「无修复版」的 HIGH，只能逐条写
# .trivyignore（2026-11 的 libsqlite3-0 CVE-2026-119373/119586 即此类：
# Installed = Candidate = 3.46.1-7+deb13u2，且 purge 会经 util-linux PreDepends
# 连锁移除 essential 包）。换成 distroless 后 OS 层只剩
#   libc6 / libssl3t64 / libzstd1 / zlib1g（base）
#   + libgcc-s1 / libstdc++6 / libgomp1 / gcc-14-base（cc）
# 没有 shell、包管理器与 apt 元数据，CVE 面与攻击面同步收窄。
# 兼容性取证（2026-11-02，GHCR 现网镜像内二进制 ldd）：NEEDED 仅
# libgcc_s.so.1 / libm.so.6 / libc.so.6 + ld-linux —— cc-debian13 全覆盖；
# gmp/mpfr 由 gmp-mpfr-sys 静态编入，不产生动态依赖。
# TLS 走 rustls/ring（纯 Rust，Cargo.lock 无 openssl-sys），不依赖系统 libssl。
FROM gcr.io/distroless/cc-debian13

# REQ-20 非 root：保持迁移前的 UID/GID 1000（原 `USER mpc` 同一数字），
# 避免 compose 侧挂载属主假设变化。distroless 无 useradd，进程按数字 UID
# 运行即可；但镜像内没有 1000 的 passwd 条目，HOME 必须显式给
# （迁移前由 passwd 推导为 /home/mpc）。
USER 1000:1000
ENV HOME=/home/mpc

COPY --from=builder /app/mpc-engine/target/release/mpc-engine /usr/local/bin/mpc-engine
# busybox 静态二进制（~1.2MB，无动态依赖）：仅用于 compose healthcheck（nc -z）。
# distroless 无 shell/curl/wget，注入单个静态 busybox 提供 nc 子命令，不引入
# shell 与包管理器（与 Java 模块 2026-08-28 DAST 修复同款做法；版本与 Java
# 模块的 dir-builder 同钉 1.36.1-musl，避免浮动 tag）。
COPY --from=busybox:1.36.1-musl /bin/busybox /busybox

# 份额落盘目录（NXC1 信封）预建并归属运行 UID——**关键**：named volume / PVC
# 首次使用时 Docker/K8s 会把镜像内的目录内容与属主复制进卷；若目录不存在，
# 卷由 root 创建，非 root 进程写入即 Permission denied，keygen 会在落盘阶段
# 失败（`keygen share persist failed`，2026-10-08 compose 沙箱实测）。
# 三个路径覆盖三种挂载约定：
#   /app/data/sessions  —— docker-compose.yml / .prod.yml（卷挂 /app/data）
#   /app/sessions       —— helm chart（PVC 挂 /app/sessions；K8s 侧 fsGroup 兜底）
#   /home/mpc/sessions  —— 手工 docker run 的默认友好路径
COPY --from=builder --chown=1000:1000 /app/runtime-dirs/ /

WORKDIR /home/mpc

EXPOSE 50051
ENV RUST_LOG=info \
    MPC_ENGINE_HOST=0.0.0.0 \
    MPC_ENGINE_PORT=50051

CMD ["mpc-engine"]
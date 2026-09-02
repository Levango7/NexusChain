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
WORKDIR /app/mpc-engine
RUN cargo build --release

# === 运行阶段 ===
FROM debian:bookworm-slim

# ca-certificates 便于出站 HTTPS（如拉取参与方元数据）；glibc 与 rust:1.80(bookworm) 一致。
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# === REQ-20 安全加固：容器非 root 运行 ===
# 创建非特权用户 mpc (UID 1000, GID 1000)，避免容器内进程以 root 身份运行
RUN groupadd -r -g 1000 mpc \
    && useradd -r -u 1000 -g mpc -m -d /home/mpc mpc

COPY --from=builder /app/mpc-engine/target/release/mpc-engine /usr/local/bin/mpc-engine

# 工作目录归属非 root 用户，确保运行时可写日志/临时文件
WORKDIR /home/mpc
RUN chown -R mpc:mpc /home/mpc

EXPOSE 50051
ENV RUST_LOG=info \
    MPC_ENGINE_HOST=0.0.0.0 \
    MPC_ENGINE_PORT=50051

# 切换到非 root 用户运行主进程
USER mpc

CMD ["mpc-engine"]
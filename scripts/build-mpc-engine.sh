#!/usr/bin/env bash
# ============================================================
# mpc-engine Rust 编译脚本（Docker 方式，Windows 无 MinGW 兼容）
#
# 背景：本机 rustc 为 windows-gnu target，缺 MinGW gcc/dlltool 链接器
# （历史记录 #11"缺 C 编译器"）。Docker 方式用 rust:latest（自带 gcc）
# + protobuf-compiler 完整编译，产出 Linux ELF。
#
# 用法: bash scripts/build-mpc-engine.sh
# 产物: mpc-engine/target/release/mpc-engine（Linux ELF）
# ============================================================
set -e
cd "$(dirname "$0")/.."

echo "[1/2] 构建带 protoc 的 rust 编译镜像（缓存，仅首次慢）..."
docker build -t nexus-rust-build -f - . <<'DOCKERFILE'
FROM rust:latest
RUN apt-get update -qq && apt-get install -y -qq protobuf-compiler
DOCKERFILE

echo "[2/2] 编译 mpc-engine（release）..."
rm -f mpc-engine/target/*.lock mpc-engine/target/release/.cargo-lock 2>/dev/null || true
docker run --rm -v "F:/Nexus/NexusChain/mpc-engine:/src" -w //src nexus-rust-build \
  cargo build --release

echo "✅ 编译完成: mpc-engine/target/release/mpc-engine"
ls -la mpc-engine/target/release/mpc-engine

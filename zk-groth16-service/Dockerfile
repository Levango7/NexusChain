# zk-groth16-service Docker 镜像
# 构建: docker build -t nexus-zk-groth16 -f zk-groth16-service/Dockerfile .
# 运行: docker run -p 50061:50061 -p 50062:50062 \
#         -e GROTH16_SETUP_DIR=/setup -v <setup-vol>:/setup nexus-zk-groth16

FROM rust:1.85-slim AS build
WORKDIR /src
RUN apt-get update -qq && apt-get install -y -qq protobuf-compiler
COPY . .
# CI 构建上下文为 repo 根目录，Cargo.toml 在 zk-groth16-service/ 子目录。
WORKDIR /src/zk-groth16-service
RUN cargo build --release
# distroless 运行阶段没有 shell/RUN：/setup 在构建阶段造好（0700）再 COPY，
# 以保持迁移前的权限位。见运行阶段的属主说明。
RUN mkdir -p -m 0700 /src/runtime-dirs/setup

# === 运行阶段 ===
# 2026-11-02：debian:trixie-slim → gcr.io/distroless/cc-debian13。
# 动机与兼容性取证见 mpc-engine/Dockerfile 同批注释（2026-11-02 对 GHCR 现网
# 镜像内二进制 ldd：NEEDED 仅 libgcc_s.so.1 / libm.so.6 / libc.so.6 +
# ld-linux，cc-debian13 全覆盖）。trixie-slim 的 apt/util-linux/libsqlite3-0
# 等整层 OS 包随之消失，不再逐条豁免 OS CVE。
# **本批不改运行 UID**：迁移前无 USER 指令（root），这里沿用（不加 :nonroot）。
# 原因：/setup 是 0700 root 的持久化命名卷（docker-compose.prod.yml
# zk-groth16-setup），改 nonroot 后**既有卷**仍归 root，升级即读不到 pk.bin；
# 降权必须随一次显式的 chown 迁移一起做，属于独立改动（避免本 PR 混入
# 不可回滚的运维步骤）。因此本文件与 mpc-engine 的 UID 策略不同是刻意的。
FROM gcr.io/distroless/cc-debian13

COPY --from=build /src/zk-groth16-service/target/release/zk-groth16-service /usr/local/bin/zk-groth16-service
# busybox 静态二进制（~1.2MB，无动态依赖）：仅用于 compose healthcheck（nc -z）。
# distroless 无 shell，探针不能再用 bash /dev/tcp（依据与版本钉法见
# mpc-engine/Dockerfile 同批注释）。
COPY --from=busybox:1.36.1-musl /bin/busybox /busybox
# 可信设置目录（外部仪式产出经卷挂载或 API 导入）——distroless 无 RUN，
# 目录由构建阶段造好（0700）后 COPY，权限位与属主（root）均与迁移前一致。
COPY --from=build /src/runtime-dirs/ /

EXPOSE 50061 50062
ENV ZK_SERVICE_PORT=50061 \
    ZK_HTTP_PORT=50062 \
    GROTH16_SETUP_DIR=/setup
ENTRYPOINT ["zk-groth16-service"]

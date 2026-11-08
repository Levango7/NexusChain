# zk-groth16-service Docker 镜像
# 构建: docker build -t nexus-zk-groth16 -f zk-groth16-service/Dockerfile .
# 运行: docker run -p 50061:50061 -p 50062:50062 \
#         -e GROTH16_SETUP_DIR=/setup -v <setup-vol>:/setup nexus-zk-groth16
# 进程以 UID/GID 65532 运行（distroless nonroot）：命名卷由 compose 的
# zk-groth16-setup-init 迁移属主；手工绑定的宿主目录需 chown 65532 后使用。

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
# 2026-11-02（其后）：cc-debian13 → cc-debian13:nonroot（UID/GID 65532）。
# 降权唯一的硬约束是 /setup 持久化卷：旧镜像无 USER（root），既有命名卷里的
# <fingerprint>/ 目录是 0700 root，直接切 nonroot 会让升级后的进程读不到 pk.bin。
# 故配套一次**幂等属主迁移**：两个 compose 文件里的 zk-groth16-setup-init
# （busybox:1.36.1-musl，`chown -R 65532:65532 /setup`），zk 以
# service_completed_successfully 等它成功退出才启动。全新部署不依赖该迁移：
# 镜像内 /setup 已归 65532，命名卷首次挂载会复制镜像内属主。
# GROTH16_EXTERNAL_SETUP_DIR 挂载（仪式产出，只读）需确保对 65532 可读。
# 固定 digest（2026-11-06 取自 master run 42176885852 的 BuildKit 解析）；
# 运行阶段 cc-debian13:nonroot 与构建阶段 rust:1.85-slim 必须成对升级：glibc 版本
# 只允许「运行镜像 >= 构建镜像」，单独升运行镜像勿降级构建链，单独升构建链勿降级运行镜像，
# 否则运行时报 GLIBC_x.y not found。
FROM gcr.io/distroless/cc-debian13:nonroot@sha256:08476c244888bfb425bae1ae4dd7c17f093e972aab8426883d97f3c6f280f7c1

COPY --from=build /src/zk-groth16-service/target/release/zk-groth16-service /usr/local/bin/zk-groth16-service
# busybox 静态二进制（~1.2MB，无动态依赖）：仅用于 compose healthcheck（nc -z）。
# distroless 无 shell，探针不能再用 bash /dev/tcp（依据与版本钉法见
# mpc-engine/Dockerfile 同批注释）。
COPY --from=busybox:1.36.1-musl /bin/busybox /busybox
# 可信设置目录（外部仪式产出经卷挂载或 API 导入）——distroless 无 RUN，
# 目录由构建阶段造好（0700）后 COPY；属主随运行 UID 改为 65532。
# 第一次挂载到空命名卷时，Docker 会把这里的属主/权限位复制进卷。
COPY --from=build --chown=65532:65532 /src/runtime-dirs/ /

EXPOSE 50061 50062
ENV ZK_SERVICE_PORT=50061 \
    ZK_HTTP_PORT=50062 \
    GROTH16_SETUP_DIR=/setup
# 绝对路径（不依赖 PATH；distroless 的 PATH 无保证）
ENTRYPOINT ["/usr/local/bin/zk-groth16-service"]

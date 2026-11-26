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
# 2026-11-25：cc-debian13 → static-debian13:nonroot + 显式最小动态运行集。
# 现网二进制 NEEDED 仅 libgcc_s.so.1 / libm.so.6 / libc.so.6 + ld-linux；
# 加载器与三项动态库从同一 builder 复制，glibc 版本与编译环境严格一致。
# UID/GID 65532 与 /setup 属主约定不变；未使用的 OpenSSL/libzstd 不再进入运行面。
FROM gcr.io/distroless/static-debian13:nonroot@sha256:8ce6dd87e9823c5b48dcfb2c8604714ccb351ce59607327bcc4957448da279b5
ENV LD_LIBRARY_PATH=/runtime-libs
COPY --from=build /usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2
COPY --from=build /usr/lib/x86_64-linux-gnu/libc.so.6 /usr/lib/x86_64-linux-gnu/libm.so.6 /usr/lib/x86_64-linux-gnu/libgcc_s.so.1 /runtime-libs/

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

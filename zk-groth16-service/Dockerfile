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

FROM debian:bookworm-slim
# apt-get upgrade -y（2026-09-14）：闭 base 镜像重建滞后的 CVE 窗口期
#（libpcre2-8-0 CVE-2026-86145/89161 HIGH 阻断实证，见 mpc-engine 同批注释）
RUN apt-get update -qq && apt-get upgrade -y -qq && apt-get install -y -qq ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/zk-groth16-service/target/release/zk-groth16-service /usr/local/bin/zk-groth16-service
# 可信设置目录（外部仪式产出经卷挂载或 API 导入）
RUN mkdir -p /setup && chmod 0700 /setup
EXPOSE 50061 50062
ENV ZK_SERVICE_PORT=50061 \
    ZK_HTTP_PORT=50062 \
    GROTH16_SETUP_DIR=/setup
ENTRYPOINT ["zk-groth16-service"]

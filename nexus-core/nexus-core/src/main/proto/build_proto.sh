#!/usr/bin/env bash
#
# 生成 P2P 消息类。
#
# 依赖：protoc 3.25.5（与 build.gradle 的 protobufVersion 保持一致），
# 且 protoc 发行包自带 google/protobuf/*.proto（timestamp.proto）。
#
# 说明：NexusChainGrpc.java 是既有 gRPC stub，服务定义（ConPay.Entry）未变化，
# 本脚本只生成消息类，避免引入额外 protoc-gen-grpc-java 依赖。
set -euo pipefail
cd "$(dirname "$0")"

protoc -I . --java_out=../java NexusChain.proto

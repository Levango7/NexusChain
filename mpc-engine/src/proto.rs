//! 由 build.rs 通过 tonic-build 从 `proto/mpc_crypto.proto` 生成的 gRPC stub 包装。
//!
//! 生成产物位于 `OUT_DIR/nexus.mpc.rs`（proto 的 package 为 `nexus.mpc`）。

// tonic/prost 生成的 gRPC stub 返回 Result<_, tonic::Status>，其中 tonic::Status
// 为 176 字节，触发 clippy::result_large_err（perf lint，阈值 128 字节）。
// 该类型由 tonic 框架定义，无法修改；在生成代码上抑制此 lint。
#![allow(clippy::result_large_err)]

pub mod mpc_crypto {
    tonic::include_proto!("nexus.mpc");
}

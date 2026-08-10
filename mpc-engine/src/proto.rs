//! 由 build.rs 通过 tonic-build 从 `proto/mpc_crypto.proto` 生成的 gRPC stub 包装。
//!
//! 生成产物位于 `OUT_DIR/nexus.mpc.rs`（proto 的 package 为 `nexus.mpc`）。

pub mod mpc_crypto {
    tonic::include_proto!("nexus.mpc");
}

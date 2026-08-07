//! 由 build.rs 通过 tonic-build 从 `proto/mpc_crypto.proto` 生成的 gRPC stub 包装。
//!
//! 生成产物位于 `OUT_DIR/mpc_crypto.rs`，`tonic::include_proto!` 宏将其引入。

pub mod mpc_crypto {
    tonic::include_proto!("mpc_crypto");
}
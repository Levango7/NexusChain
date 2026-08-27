//! mpc-engine 库 target（供 tests/integration_test.rs 集成测试复用 proto 模块）。
//!
//! 二进制入口为 `src/main.rs`（含全部服务实现）；lib target 仅暴露
//! `proto` 模块（gRPC stub），避免集成测试与 bin 的模块结构耦合。
//!
//! **P2-T4（B2）**：集成测试 `use mpc_engine::proto::mpc_crypto::...`
//! 依赖此 lib target——此前缺失导致集成测试编译失败（`cannot find
//! module or crate mpc_engine`），本修复解除该预存缺陷。

pub mod proto;

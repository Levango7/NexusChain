//! mpc-engine 库 target（供 tests/integration_test.rs 集成测试复用 proto 模块）。
//!
//! 二进制入口为 `src/main.rs`（含全部服务实现）；lib target 暴露
//! `proto` 与 `cggmp` 模块（gRPC stub + CGGMP21 基础设施），让集成测试
//! 与 F-U 1 验证可复用生产代码路径，避免与 bin 的模块结构耦合。
//!
//! **P2-T4（B2）**：集成测试 `use mpc_engine::proto::mpc_crypto::...`
//! 依赖此 lib target——此前缺失导致集成测试编译失败（`cannot find
//! module or crate mpc_engine`），本修复解除该预存缺陷。
//!
//! **v2.2.0 D 批（F-U 1 验证）**：`cggmp` 暴露使三方 DKG Simulation 端到端
//! 跑通；批量测试三方 IncompleteKeyShare 产出与 validate 通过。

pub mod cggmp;
pub mod proto;

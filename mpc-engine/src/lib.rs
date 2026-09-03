//! mpc-engine 库 target：全部业务模块经此暴露（bin 为薄壳）。
//!
//! 二进制入口为 `src/main.rs`（仅启动装配）；lib target 持有全部模块——
//! 集成测试（tests/*.rs）经 `mpc_engine::proto`/`mpc_engine::server` 等
//! 复用生产代码路径，避免与 bin 的模块结构耦合。
//!
//! **P2-T4（B2）**：集成测试 `use mpc_engine::proto::mpc_crypto::...`
//! 依赖此 lib target——此前缺失导致集成测试编译失败（`cannot find
//! module or crate mpc_engine`），该修复解除预存缺陷。
//!
//! **v2.2.0 D 批（F-U 1 验证）**：`cggmp` 暴露使三方 DKG Simulation 端到端
//! 跑通；批量测试三方 IncompleteKeyShare 产出与 validate 通过。
//!
//! **v2.2.0 E 批**：`cggmp_state` 暴露驱动线程 actor——tests/cggmp_threshold_e2e.rs
//! 经 `CgDriverHandle` 完成三方 keygen→aux→合成→2-of-3 sign→verify 里程碑。
//!
//! **v2.2.0 F 批（模块声明自 bin 迁入）**：全部业务模块迁到 lib（main.rs
//! 薄壳化），tests/cggmp_rpc_e2e.rs 经 `mpc_engine::server` 起进程内 tonic
//! server 完成 RPC 面验收——不再复制业务模块树。

// gRPC 服务方法返回 Result<_, tonic::Status>，tonic::Status 为 176 字节，
// 触发 clippy::result_large_err（perf lint，阈值 128 字节）。
// tonic::Status 由框架定义无法修改，在 crate 级别抑制此 lint。
//
// dead_code：部分工具函数/结构体在 tls feature 未启用时未使用，属条件编译正常现象。
#![allow(clippy::result_large_err, dead_code, clippy::needless_range_loop)]

pub mod aggregate;
pub mod cggmp;
// E 批：CGGMP21 会话驱动层——驱动线程 actor（!Send 状态机独占线程）。
pub mod cggmp_state;
pub mod config;
pub mod distributed;
pub mod dkg;
pub mod gg20;
pub mod persistence;
pub mod proto;
pub mod server;
pub mod session;
pub mod sign;

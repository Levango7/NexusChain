//! mpc-engine 入口：初始化日志、读取配置、启动 tonic gRPC 服务端。
//!
//! 监听地址由环境变量控制：
//!   * `MPC_ENGINE_HOST`（默认 `0.0.0.0`）
//!   * `MPC_ENGINE_PORT`（默认 `50051`）
//!   * `RUST_LOG`（tracing 日志级别，默认 `info`）
//!
//! 审计报告 §4.1 方案 A：Rust 密码学引擎独立进程，signing-service 通过本地 gRPC 调用。

use std::net::SocketAddr;

use tracing_subscriber::EnvFilter;

mod aggregate;
mod dkg;
mod proto;
mod server;
mod sign;

use server::MpcCryptoServiceImpl;

#[tokio::main]
async fn main() -> eyre::Result<()> {
    // === 日志初始化 ===
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::fmt().with_env_filter(filter).init();

    // === 配置读取 ===
    let host = std::env::var("MPC_ENGINE_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
    let port: u16 = std::env::var("MPC_ENGINE_PORT")
        .unwrap_or_else(|_| "50051".to_string())
        .parse()
        .map_err(|e| eyre::eyre!("invalid MPC_ENGINE_PORT: {e}"))?;
    let bind: SocketAddr = format!("{host}:{port}")
        .parse()
        .map_err(|e| eyre::eyre!("invalid bind address {host}:{port}: {e}"))?;

    tracing::info!(
        %bind,
        version = env!("CARGO_PKG_VERSION"),
        "starting mpc-engine gRPC server (§4.1 方案 A)"
    );

    // === 启动 gRPC 服务端 ===
    let svc = MpcCryptoServiceImpl::default();
    let server = proto::mpc_crypto::mpc_crypto_service_server::MpcCryptoServiceServer::new(svc);

    tonic::transport::Server::builder()
        .add_service(server)
        .serve(bind)
        .await?;

    Ok(())
}
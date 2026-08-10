//! mpc-engine 入口：初始化日志、读取配置、启动 tonic gRPC 服务端。
//!
//! 监听地址由环境变量控制：
//!   * `MPC_ENGINE_HOST`（默认 `0.0.0.0`）
//!   * `MPC_ENGINE_PORT`（默认 `50051`）
//!   * `RUST_LOG`（tracing 日志级别，默认 `info`）
//!
//! TLS 配置由环境变量控制（MPC-P0-01 修复）：
//!   * `MPC_TLS_CERT_PATH`：TLS 证书路径（PEM 格式）
//!   * `MPC_TLS_KEY_PATH`：TLS 私钥路径（PEM 格式）
//!
//! 当两个环境变量都存在时，启用 TLS；否则记录警告并使用明文（开发模式）。
//!
//! **启用 TLS 编译**：tonic 的 `tls` feature 默认未启用。要启用 TLS 支持，
//! 在 `Cargo.toml` 中添加：
//! ```toml
//! [features]
//! tls = ["tonic/tls"]
//! ```
//! 然后使用 `cargo build --features tls` 编译。本文件使用 `#[cfg(feature = "tls")]`
//! 条件编译，未启用 `tls` feature 时 TLS 代码被排除，仍可正常编译。
//!
//! 审计报告 §4.1 方案 A：Rust 密码学引擎独立进程，signing-service 通过本地 gRPC 调用。

use std::net::SocketAddr;

use tracing_subscriber::EnvFilter;

mod aggregate;
mod dkg;
mod gg20;
mod proto;
mod server;
mod sign;

#[cfg(feature = "tls")]
use tonic::transport::ServerTlsConfig;

use server::MpcCryptoServiceImpl;

/// 读取 TLS 证书与私钥路径环境变量。
///
/// 返回 `Some((cert_path, key_path))` 当且仅当 `MPC_TLS_CERT_PATH` 与
/// `MPC_TLS_KEY_PATH` 都已设置且非空；否则返回 `None`。
fn read_tls_env() -> Option<(String, String)> {
    let cert = std::env::var("MPC_TLS_CERT_PATH").ok().filter(|s| !s.is_empty());
    let key = std::env::var("MPC_TLS_KEY_PATH").ok().filter(|s| !s.is_empty());
    match (cert, key) {
        (Some(c), Some(k)) => Some((c, k)),
        _ => None,
    }
}

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

    // === TLS 配置读取（MPC-P0-01） ===
    let tls_config = read_tls_env();

    tracing::info!(
        %bind,
        tls_enabled = tls_config.is_some(),
        version = env!("CARGO_PKG_VERSION"),
        "starting mpc-engine gRPC server (§4.1 方案 A, MPC-P0-01 TLS 修复)"
    );

    // === 启动 gRPC 服务端 ===
    let svc = MpcCryptoServiceImpl::default();
    let server = proto::mpc_crypto::mpc_crypto_service_server::MpcCryptoServiceServer::new(svc);

    // MPC-P0-01: TLS 配置
    // 当 MPC_TLS_CERT_PATH 与 MPC_TLS_KEY_PATH 都存在时启用 TLS；
    // 否则记录警告并使用明文（开发模式）。
    #[cfg(feature = "tls")]
    {
        if let Some((cert_path, key_path)) = &tls_config {
            tracing::info!(
                cert_path = %cert_path,
                key_path = %key_path,
                "TLS enabled: loading certificate and private key"
            );
            let cert = std::fs::read(cert_path)
                .map_err(|e| eyre::eyre!("failed to read TLS cert '{}': {e}", cert_path))?;
            let key = std::fs::read(key_path)
                .map_err(|e| eyre::eyre!("failed to read TLS key '{}': {e}", key_path))?;
            let identity = tonic::transport::Identity::from_pem(cert, key);
            let tls_config = ServerTlsConfig::new().identity(identity);
            tonic::transport::Server::builder()
                .tls_config(tls_config)?
                .add_service(server)
                .serve(bind)
                .await?;
            return Ok(());
        }
    }

    // 明文模式（开发）或 TLS feature 未启用
    #[cfg(not(feature = "tls"))]
    {
        if tls_config.is_some() {
            tracing::warn!(
                "MPC_TLS_CERT_PATH and MPC_TLS_KEY_PATH are set but tonic 'tls' feature \
                 is not enabled — falling back to PLAINTEXT. \
                 To enable TLS, add [features] tls = [\"tonic/tls\"] to Cargo.toml \
                 and rebuild with --features tls"
            );
        } else {
            tracing::warn!(
                "MPC_TLS_CERT_PATH or MPC_TLS_KEY_PATH not set — using PLAINTEXT gRPC. \
                 NOT for production; set both env vars and enable tls feature \
                 to enable TLS (design §7.1 R10, MPC-P0-01)"
            );
        }
    }

    #[cfg(feature = "tls")]
    {
        if tls_config.is_none() {
            tracing::warn!(
                "MPC_TLS_CERT_PATH or MPC_TLS_KEY_PATH not set — using PLAINTEXT gRPC. \
                 NOT for production; set both env vars to enable TLS \
                 (design §7.1 R10, MPC-P0-01)"
            );
        }
    }

    tonic::transport::Server::builder()
        .add_service(server)
        .serve(bind)
        .await?;

    Ok(())
}

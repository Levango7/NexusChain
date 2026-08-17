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
//! **TLS 强制策略**（`MPC_REQUIRE_TLS`）：
//!   * `MPC_REQUIRE_TLS=true`：当未配置 TLS 证书/私钥时，**拒绝启动**（返回错误），
//!     不降级到明文。适用于生产环境，防止误配置导致明文 gRPC 暴露。
//!   * `MPC_REQUIRE_TLS` 未设置或为 `false`：保持当前行为（降级到明文并记录警告），
//!     适用于开发环境。
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
mod persistence;
mod proto;
mod server;
mod sign;

#[cfg(feature = "tls")]
use tonic::transport::ServerTlsConfig;

use server::{AuthInterceptor, MpcCryptoServiceImpl};

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

    // === MPC_REQUIRE_TLS 强制策略 ===
    // 当 MPC_REQUIRE_TLS=true 且未配置 TLS 证书/私钥时，拒绝启动（不降级到明文）。
    // 当 MPC_REQUIRE_TLS 未设置或为 false 时，保持当前行为（降级到明文并记录警告）。
    let require_tls = std::env::var("MPC_REQUIRE_TLS")
        .ok()
        .filter(|s| !s.is_empty())
        .map(|s| s.eq_ignore_ascii_case("true") || s == "1")
        .unwrap_or(false);

    tracing::info!(
        %bind,
        tls_enabled = tls_config.is_some(),
        require_tls,
        version = env!("CARGO_PKG_VERSION"),
        "starting mpc-engine gRPC server (§4.1 方案 A, MPC-P0-01 TLS 修复, MPC_REQUIRE_TLS 策略)"
    );

    // MPC_REQUIRE_TLS=true 但未配置 TLS：拒绝启动
    if require_tls && tls_config.is_none() {
        return Err(eyre::eyre!(
            "MPC_REQUIRE_TLS=true but MPC_TLS_CERT_PATH/MPC_TLS_KEY_PATH not set. \
             Refusing to start with plaintext gRPC. \
             Set both TLS env vars and enable tls feature to start securely \
             (design §7.1 R10, MPC-P0-01)"
        ));
    }

    // === MPC-P1-05: gRPC 应用层认证拦截器 ===
    // 读取 MPC_AUTH_TOKEN 环境变量。设置时启用 Bearer token 认证；
    // 未设置时跳过校验（开发模式），但 MPC_REQUIRE_AUTH=true 时拒绝启动。
    let auth_token = std::env::var("MPC_AUTH_TOKEN")
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_default();
    let auth_enabled = !auth_token.is_empty();
    let require_auth = std::env::var("MPC_REQUIRE_AUTH")
        .ok()
        .filter(|s| !s.is_empty())
        .map(|s| s.eq_ignore_ascii_case("true") || s == "1")
        .unwrap_or(false);

    if require_auth && !auth_enabled {
        return Err(eyre::eyre!(
            "MPC_REQUIRE_AUTH=true but MPC_AUTH_TOKEN not set. \
             Refusing to start without gRPC auth. \
             Set MPC_AUTH_TOKEN to a non-empty Bearer token value (MPC-P1-05)"
        ));
    }

    if auth_enabled {
        tracing::info!("MPC-P1-05: gRPC auth interceptor enabled (Bearer token)");
    } else {
        tracing::warn!(
            "MPC_AUTH_TOKEN not set — gRPC auth DISABLED (development mode). \
             NOT for production; set MPC_AUTH_TOKEN to enable auth (MPC-P1-05)"
        );
    }

    // === 启动 gRPC 服务端 ===
    let svc = MpcCryptoServiceImpl::default();
    let server = proto::mpc_crypto::mpc_crypto_service_server::MpcCryptoServiceServer::new(svc)
        .interceptor(AuthInterceptor::new(auth_token));

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
        // MPC_REQUIRE_TLS=true 但 tls feature 未启用：拒绝启动
        // （即使配置了证书路径，feature 未启用也无法使用 TLS，会降级到明文）
        if require_tls {
            return Err(eyre::eyre!(
                "MPC_REQUIRE_TLS=true but tonic 'tls' feature is not enabled at compile time. \
                 Refusing to start with plaintext gRPC. \
                 Rebuild with --features tls (add [features] tls = [\"tonic/tls\"] to Cargo.toml) \
                 (design §7.1 R10, MPC-P0-01)"
            ));
        }
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
            // 此分支仅在 require_tls=false 时可达（require_tls=true 且 tls_config.is_none()
            // 已在上方提前返回错误）
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

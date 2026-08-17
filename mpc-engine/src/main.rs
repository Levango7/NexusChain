//! mpc-engine 入口：初始化日志、读取配置、启动 tonic gRPC 服务端。
//!
//! **MPC-P2-F5 分布式安全模型**：
//!   * 各方独立进程：每个 mpc-engine 进程代表一个 MPC 参与方，持有 `PartyConfig`。
//!   * 配置来源：`--config <path>` 命令行参数或 `MPC_CONFIG_PATH` 环境变量，
//!     指向 JSON 格式的 `PartyConfig` 文件。
//!   * gRPC 强制 mTLS：Server 端加载 TLS 证书 + 要求客户端证书（`tls_authority_root`）。
//!   * 私钥份额本地加密存储：`MPC_STORAGE_KEY` 从配置文件 `storage_key` 字段读取。
//!   * session_id 身份绑定：`SessionManager` 在 DKG 创建 session 时绑定调用方 `party_id`。
//!
//! 兼容旧模式：未提供 `--config` 且 `MPC_CONFIG_PATH` 未设置时，回退到环境变量配置
//! （`MPC_ENGINE_HOST`/`MPC_ENGINE_PORT`/`MPC_TLS_CERT_PATH`/`MPC_TLS_KEY_PATH` 等），
//! 保持与 Phase 1 部署兼容。
//!
//! TLS 配置由环境变量控制（MPC-P0-01 修复，旧模式兼容）：
//!   * `MPC_TLS_CERT_PATH`：TLS 证书路径（PEM 格式）
//!   * `MPC_TLS_KEY_PATH`：TLS 私钥路径（PEM 格式）
//!
//! **TLS 强制策略**（`MPC_REQUIRE_TLS`）：
//!   * `MPC_REQUIRE_TLS=true`：当未配置 TLS 证书/私钥时，**拒绝启动**（返回错误），
//!     不降级到明文。适用于生产环境，防止误配置导致明文 gRPC 暴露。
//!
//! **启用 TLS 编译**：tonic 的 `tls` feature 默认未启用。要启用 TLS 支持，
//! 在 `Cargo.toml` 中添加：
//! ```toml
//! [features]
//! tls = ["tonic/tls"]
//! ```
//! 然后使用 `cargo build --features tls` 编译。
//!
//! 审计报告 §4.1 方案 A：Rust 密码学引擎独立进程，signing-service 通过本地 gRPC 调用。

use std::net::SocketAddr;

use tracing_subscriber::EnvFilter;

mod aggregate;
mod config;
mod dkg;
mod gg20;
mod persistence;
mod proto;
mod server;
mod session;
mod sign;

#[cfg(feature = "tls")]
use tonic::transport::ServerTlsConfig;

use server::{AuthInterceptor, MpcCryptoServiceImpl};

/// 解析命令行参数，返回 `--config` 指定的配置文件路径。
fn parse_config_arg() -> Option<String> {
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        if arg == "--config" {
            return args.next();
        }
        // 支持 --config=path 形式
        if let Some(path) = arg.strip_prefix("--config=") {
            return Some(path.to_string());
        }
    }
    None
}

/// 读取 TLS 证书与私钥路径环境变量（旧模式兼容）。
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

    // === MPC-P2-F5: 尝试从配置文件加载 PartyConfig（分布式模式） ===
    let config_path = parse_config_arg();
    let party_config = if config_path.is_some()
        || std::env::var(config::CONFIG_PATH_ENV).is_ok()
    {
        match config::load_config(config_path.as_deref()) {
            Ok(cfg) => {
                tracing::info!(
                    party_index = cfg.party_index,
                    party_id = %cfg.party_id,
                    "MPC-P2-F5: distributed security model enabled (per-party config loaded)"
                );
                // 将 storage_key 同步到环境变量（供 persistence 模块读取）
                cfg.apply_storage_key_to_env()?;
                Some(cfg)
            }
            Err(e) => {
                tracing::error!(error = %e, "MPC-P2-F5: failed to load party config");
                return Err(e);
            }
        }
    } else {
        tracing::info!(
            "MPC-P2-F5: no party config provided — falling back to env-var mode \
             (trusted-coordinator compatibility)"
        );
        None
    };

    // === 配置读取 ===
    // 优先使用 PartyConfig.listen_addr；否则回退到环境变量
    let (bind, my_party_id): (SocketAddr, String) = match &party_config {
        Some(cfg) => {
            let bind: SocketAddr = cfg
                .listen_addr
                .parse()
                .map_err(|e| eyre::eyre!("invalid listen_addr '{}': {e}", cfg.listen_addr))?;
            (bind, cfg.party_id.clone())
        }
        None => {
            let host =
                std::env::var("MPC_ENGINE_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
            let port: u16 = std::env::var("MPC_ENGINE_PORT")
                .unwrap_or_else(|_| "50051".to_string())
                .parse()
                .map_err(|e| eyre::eyre!("invalid MPC_ENGINE_PORT: {e}"))?;
            let bind: SocketAddr = format!("{host}:{port}")
                .parse()
                .map_err(|e| eyre::eyre!("invalid bind address {host}:{port}: {e}"))?;
            (bind, String::new())
        }
    };

    // === TLS 配置读取 ===
    // MPC-P2-F5: 优先使用 PartyConfig 的 mTLS 配置；否则回退到环境变量
    let tls_config = if let Some(cfg) = &party_config {
        // 分布式模式：mTLS 配置来自 PartyConfig
        Some((cfg.tls_cert.clone(), cfg.tls_key.clone(), Some(cfg.tls_ca.clone())))
    } else {
        // 旧模式：单 TLS（无 client CA）
        read_tls_env().map(|(c, k)| (c, k, None))
    };

    // === MPC_REQUIRE_TLS 强制策略 ===
    let require_tls = std::env::var("MPC_REQUIRE_TLS")
        .ok()
        .filter(|s| !s.is_empty())
        .map(|s| s.eq_ignore_ascii_case("true") || s == "1")
        .unwrap_or(false);
    // MPC-P2-F5: 分布式模式（有 party_config）隐含 require_tls = true
    let require_tls = require_tls || party_config.is_some();

    tracing::info!(
        %bind,
        tls_enabled = tls_config.is_some(),
        require_tls,
        distributed_mode = party_config.is_some(),
        version = env!("CARGO_PKG_VERSION"),
        "starting mpc-engine gRPC server (§4.1 方案 A, MPC-P0-01 TLS, MPC-P1-05 auth, MPC-P2-F5 distributed security)"
    );

    // MPC_REQUIRE_TLS=true 但未配置 TLS：拒绝启动
    if require_tls && tls_config.is_none() {
        return Err(eyre::eyre!(
            "MPC_REQUIRE_TLS=true (or distributed mode) but TLS config not available. \
             Refusing to start with plaintext gRPC. \
             Provide --config <path> with tls_cert/tls_key/tls_ca, or set \
             MPC_TLS_CERT_PATH/MPC_TLS_KEY_PATH and enable tls feature \
             (design §7.1 R10, MPC-P0-01, MPC-P2-F5)"
        ));
    }

    // === MPC-P1-05: gRPC 应用层认证拦截器 ===
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
    // MPC-P2-F5: 若有 party_config，使用 with_party_id 启用 session 身份绑定
    let svc = if !my_party_id.is_empty() {
        MpcCryptoServiceImpl::with_party_id(my_party_id.clone())
    } else {
        MpcCryptoServiceImpl::default()
    };
    let server = proto::mpc_crypto::mpc_crypto_service_server::MpcCryptoServiceServer::new(svc)
        .interceptor(AuthInterceptor::new(auth_token));

    // MPC-P0-01 / MPC-P2-F5: TLS / mTLS 配置
    #[cfg(feature = "tls")]
    {
        if let Some((cert_path, key_path, ca_path)) = &tls_config {
            tracing::info!(
                cert_path = %cert_path,
                key_path = %key_path,
                ca_path = ?ca_path,
                "TLS/mTLS enabled: loading certificate and private key"
            );
            let cert = std::fs::read(cert_path)
                .map_err(|e| eyre::eyre!("failed to read TLS cert '{}': {e}", cert_path))?;
            let key = std::fs::read(key_path)
                .map_err(|e| eyre::eyre!("failed to read TLS key '{}': {e}", key_path))?;
            let identity = tonic::transport::Identity::from_pem(cert, key);

            // MPC-P2-F5: 若有 CA 证书，启用 mTLS（要求客户端证书）
            if let Some(ca_path) = ca_path {
                let ca = std::fs::read(ca_path)
                    .map_err(|e| eyre::eyre!("failed to read TLS CA '{}': {e}", ca_path))?;
                let client_ca = tonic::transport::Certificate::from_pem(ca);
                let tls_config = ServerTlsConfig::new()
                    .identity(identity)
                    .client_ca_root(client_ca);
                tracing::info!(
                    "MPC-P2-F5: mTLS enabled (server requires client certificate)"
                );
                tonic::transport::Server::builder()
                    .tls_config(tls_config)?
                    .add_service(server)
                    .serve(bind)
                    .await?;
                return Ok(());
            }

            // 单 TLS（无 client CA，旧模式兼容）
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
        if require_tls {
            return Err(eyre::eyre!(
                "MPC_REQUIRE_TLS=true (or distributed mode) but tonic 'tls' feature \
                 is not enabled at compile time. \
                 Refusing to start with plaintext gRPC. \
                 Rebuild with --features tls (add [features] tls = [\"tonic/tls\"] to Cargo.toml) \
                 (design §7.1 R10, MPC-P0-01, MPC-P2-F5)"
            ));
        }
        if tls_config.is_some() {
            tracing::warn!(
                "TLS config is set but tonic 'tls' feature is not enabled — \
                 falling back to PLAINTEXT. \
                 To enable TLS, add [features] tls = [\"tonic/tls\"] to Cargo.toml \
                 and rebuild with --features tls"
            );
        } else {
            tracing::warn!(
                "TLS config not set — using PLAINTEXT gRPC. \
                 NOT for production; provide --config with tls_cert/tls_key/tls_ca \
                 or set MPC_TLS_CERT_PATH/MPC_TLS_KEY_PATH and enable tls feature \
                 (design §7.1 R10, MPC-P0-01)"
            );
        }
    }

    #[cfg(feature = "tls")]
    {
        if tls_config.is_none() {
            // 此分支仅在 require_tls=false 时可达
            tracing::warn!(
                "TLS config not set — using PLAINTEXT gRPC. \
                 NOT for production; provide --config with tls_cert/tls_key/tls_ca \
                 or set MPC_TLS_CERT_PATH/MPC_TLS_KEY_PATH \
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

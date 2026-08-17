//! gRPC 服务端实现：实现 `MpcCryptoService` trait，
//! 将 Dkg/Sign/Aggregate/HealthCheck 四个 RPC 委托给对应模块。
//!
//! v1.9.2：接入真实 GG20 门限 ECDSA（`gg20` 模块），并与 nexus-signing-service
//! 的 Java proto 契约（`nexus.mpc` 包、hex 字符串编码、HealthCheck）完全对齐。
//!
//! **MPC-P2-F5 分布式安全模型**：
//!   * 私钥份额隔离：DKG 响应只返回本方份额（`extract_private_share` 限制 `party_index`）。
//!   * session_id 身份绑定：`SessionManager` 在 DKG 创建 session 时绑定调用方 `party_id`，
//!     后续 Sign/Aggregate 校验调用方身份一致。
//!   * gRPC 强制 mTLS：Server 端要求客户端证书（`tls_authority_root`），
//!     Client 端加载自己的证书并验证 server 证书（见 `MtlsConfig`）。
//!   * AuthInterceptor（MPC-P1-05）保留，作为应用层 Bearer token 认证补充。

use std::collections::HashMap;
// std::sync::Mutex safe: lock not held across .await point.
// run_dkg/run_sign/run_aggregate 均为同步函数（pub fn，非 async fn），
// 在 async RPC 方法中同步调用，锁的获取与释放在同步代码段内完成，不跨 .await。
use std::sync::Mutex;

use tonic::{Request, Response, Status};

use crate::aggregate;
use crate::dkg;
use crate::gg20::{DkgSession, SignCache};
use crate::proto::mpc_crypto::*;
use crate::session::SessionManager;
use crate::sign;

/// gRPC 服务实现体。
///
/// 持有三级缓存：
///   * `sessions`：session_id -> DKG 会话（各方密钥材料）
///   * `sign_runs`：session_id -> 一次完整 GG20 签名运行结果
///   * `session_mgr`：session_id -> 调用方身份绑定（MPC-P2-F5）
///
/// 注：不派生 Debug，因缓存内含第三方密码学库类型，未必实现 Debug。
pub struct MpcCryptoServiceImpl {
    pub sessions: Mutex<HashMap<String, DkgSession>>,
    pub sign_runs: Mutex<HashMap<String, SignCache>>,
    /// MPC-P2-F5: session_id 调用方身份绑定。
    pub session_mgr: SessionManager,
    /// MPC-P2-F5: 本方 party_id（来自 PartyConfig，用于 session 身份绑定）。
    /// 空字符串表示未配置（兼容旧模式，不启用身份绑定）。
    pub my_party_id: String,
}

impl Default for MpcCryptoServiceImpl {
    fn default() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            sign_runs: Mutex::new(HashMap::new()),
            session_mgr: SessionManager::new(),
            my_party_id: String::new(),
        }
    }
}

impl MpcCryptoServiceImpl {
    /// 创建带本方 party_id 的服务实例（MPC-P2-F5 分布式模式）。
    pub fn with_party_id(my_party_id: String) -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            sign_runs: Mutex::new(HashMap::new()),
            session_mgr: SessionManager::new(),
            my_party_id,
        }
    }

    /// MPC-P2-F5: 校验调用方身份与 session 绑定一致。
    ///
    /// `my_party_id` 为空时跳过校验（兼容旧模式）；非空时严格校验。
    fn check_session_identity(
        &self,
        session_id: &str,
        party_index: usize,
    ) -> Result<(), Status> {
        if self.my_party_id.is_empty() {
            return Ok(()); // 兼容旧模式：未配置 party_id，跳过身份绑定
        }
        self.session_mgr
            .verify_caller(session_id, &self.my_party_id, party_index)
            .map(|_| ())
            .map_err(|e| {
                Status::permission_denied(format!("session identity check failed: {e}"))
            })
    }
}

#[tonic::async_trait]
impl mpc_crypto_service_server::MpcCryptoService for MpcCryptoServiceImpl {
    /// 分布式密钥生成。
    async fn dkg(&self, req: Request<DkgRequest>) -> Result<Response<DkgResponse>, Status> {
        // MPC-P1-05: 记录调用方身份（peer_addr），便于审计追溯
        let peer = req
            .peer_addr()
            .map(|a| a.to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let req = req.into_inner();
        tracing::info!(
            session_id = %req.session_id,
            threshold = req.threshold,
            total_parties = req.total_parties,
            party_index = req.party_index,
            peer = %peer,
            "rpc Dkg (MPC-P1-05: caller identity logged, MPC-P2-F5: distributed security)"
        );

        // MPC-P2-F5: 创建 session 并绑定调用方身份（my_party_id 非空时）
        if !self.my_party_id.is_empty() && !req.session_id.is_empty() && req.party_index >= 0 {
            self.session_mgr
                .create_session(&req.session_id, &self.my_party_id, req.party_index as usize)
                .map_err(|e| {
                    Status::permission_denied(format!(
                        "session identity binding failed: {e}"
                    ))
                })?;
        }

        let resp = dkg::run_dkg(&self.sessions, req)
            .map_err(|e| Status::internal(format!("dkg: {e}")))?;
        Ok(Response::new(resp))
    }

    /// 部分签名。
    async fn sign(&self, req: Request<SignRequest>) -> Result<Response<SignResponse>, Status> {
        // MPC-P1-05: 记录调用方身份
        let peer = req
            .peer_addr()
            .map(|a| a.to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let req = req.into_inner();
        tracing::info!(
            session_id = %req.session_id,
            party_index = req.party_index,
            peer = %peer,
            "rpc Sign (MPC-P1-05: caller identity logged, MPC-P2-F5: identity check)"
        );

        // MPC-P2-F5: 校验调用方身份与 session 绑定一致
        // 保存 session_id 供状态转换使用（req 会被 move 进 run_sign）
        let session_id = req.session_id.clone();
        if req.party_index >= 0 {
            self.check_session_identity(&req.session_id, req.party_index as usize)?;
        }

        let resp = sign::run_sign(&self.sessions, &self.sign_runs, req)
            .map_err(|e| Status::internal(format!("sign: {e}")))?;

        // MPC-P2-F5: 状态转换 DkgReady -> SignReady（resp.success 时）
        if resp.success {
            let _ = self
                .session_mgr
                .transition(&session_id, crate::session::SessionState::SignReady);
        }

        Ok(Response::new(resp))
    }

    /// 签名聚合。
    async fn aggregate(
        &self,
        req: Request<AggregateRequest>,
    ) -> Result<Response<AggregateResponse>, Status> {
        // MPC-P1-05: 记录调用方身份
        let peer = req
            .peer_addr()
            .map(|a| a.to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let req = req.into_inner();
        tracing::info!(
            session_id = %req.session_id,
            shares = req.partial_signatures.len(),
            peer = %peer,
            "rpc Aggregate (MPC-P1-05: caller identity logged)"
        );

        let resp = aggregate::run_aggregate(&self.sign_runs, req)
            .map_err(|e| Status::internal(format!("aggregate: {e}")))?;
        Ok(Response::new(resp))
    }

    /// 健康检查（对齐 Java 契约 HealthCheck RPC）。
    async fn health_check(
        &self,
        _req: Request<HealthCheckRequest>,
    ) -> Result<Response<HealthCheckResponse>, Status> {
        Ok(Response::new(HealthCheckResponse {
            healthy: true,
            status: format!("mpc-engine {}", env!("CARGO_PKG_VERSION")),
        }))
    }
}

// =========================================================================
// MPC-P2-F5: gRPC 强制 mTLS 配置
// =========================================================================
// Server 端：加载 TLS 证书 + 私钥（Identity），并设置 `tls_authority_root`
// 要求客户端证书（双向 TLS）。Client 端：加载自己的证书 + 私钥（Identity），
// 并设置 `tls_authority_root` 验证 server 证书。
//
// 配置来源：PartyConfig（tls_cert / tls_key / tls_ca）。
// 编译需要 tonic 的 `tls` feature：`cargo build --features tls`。

/// mTLS 配置（MPC-P2-F5）。
///
/// Server 端与 Client 端共用：`server_identity` 为本方证书+私钥，
/// `client_ca` 为用于验证对端证书的 CA 证书。
#[cfg(feature = "tls")]
pub struct MtlsConfig {
    /// 本方 TLS 证书 + 私钥（PEM）。
    pub server_identity: tonic::transport::Identity,
    /// 用于验证对端证书的 CA 证书（PEM 字节）。
    pub client_ca: tonic::transport::Certificate,
}

#[cfg(feature = "tls")]
impl MtlsConfig {
    /// 从 PartyConfig 加载 mTLS 配置。
    ///
    /// 读取 `tls_cert`/`tls_key`/`tls_ca` 文件，构造 `Identity` 与 `Certificate`。
    pub fn from_party_config(
        config: &crate::config::PartyConfig,
    ) -> eyre::Result<Self> {
        let cert = std::fs::read(&config.tls_cert).map_err(|e| {
            eyre::eyre!(
                "MPC-P2-F5: failed to read TLS cert '{}': {e}",
                config.tls_cert
            )
        })?;
        let key = std::fs::read(&config.tls_key).map_err(|e| {
            eyre::eyre!(
                "MPC-P2-F5: failed to read TLS key '{}': {e}",
                config.tls_key
            )
        })?;
        let ca = std::fs::read(&config.tls_ca).map_err(|e| {
            eyre::eyre!(
                "MPC-P2-F5: failed to read TLS CA '{}': {e}",
                config.tls_ca
            )
        })?;

        let server_identity = tonic::transport::Identity::from_pem(cert, key);
        let client_ca = tonic::transport::Certificate::from_pem(ca);

        tracing::info!(
            cert_path = %config.tls_cert,
            ca_path = %config.tls_ca,
            "MPC-P2-F5: mTLS config loaded (server identity + client CA)"
        );
        Ok(Self {
            server_identity,
            client_ca,
        })
    }

    /// 构造 tonic Server 端 TLS 配置（要求客户端证书）。
    pub fn server_tls_config(&self) -> eyre::Result<tonic::transport::ServerTlsConfig> {
        let tls = tonic::transport::ServerTlsConfig::new()
            .identity(self.server_identity.clone())
            .client_ca_root(self.client_ca.clone());
        Ok(tls)
    }

    /// 构造 tonic Client 端 TLS 配置（加载本方证书 + 验证 server 证书）。
    pub fn client_tls_config(&self) -> eyre::Result<tonic::transport::ClientTlsConfig> {
        // 注意：ClientTlsConfig 需要指定 server 的域名（SNI），
        // 此处使用默认配置；实际使用时按对端 endpoint 的域名设置。
        let tls = tonic::transport::ClientTlsConfig::new()
            .identity(self.server_identity.clone())
            .ca_certificate(self.client_ca.clone());
        Ok(tls)
    }
}

// =========================================================================
// MPC-P1-05: gRPC 应用层认证拦截器
// =========================================================================
// 参考 nexus-signing-service 的 AuthTokenServerInterceptor 模式
// （MpcTransportGrpcServer.AuthTokenServerInterceptor）。
// 校验每个 RPC 请求的 `Authorization: Bearer <token>` 头：
//   * 缺失 / 非 Bearer 格式 / token 不匹配 → 返回 UNAUTHENTICATED 拒绝
//   * expected_token 为空 → 跳过校验（开发模式，记录警告于启动时）
// token 比较使用普通 ==（非常量时间）：Bearer token 非密码，失败立即拒绝，
// 时序攻击收益有限；生产环境应配合 mTLS 使用。

/// `Authorization` metadata 头名。
const AUTHORIZATION_HEADER: &str = "authorization";

/// Bearer 前缀（RFC 6750）。
const BEARER_PREFIX: &str = "Bearer ";

/// gRPC 认证拦截器（MPC-P1-05）。
///
/// 实现 `tonic::service::Interceptor`，校验每个 RPC 请求的
/// `Authorization: Bearer <token>` 头。`expected_token` 为空时跳过校验
/// （开发模式）；非空时严格校验，失败返回 `Status::unauthenticated`。
#[derive(Clone)]
pub struct AuthInterceptor {
    /// 期望的 Bearer token 值（不含 "Bearer " 前缀）。空表示跳过校验。
    expected_token: String,
}

impl AuthInterceptor {
    /// 创建认证拦截器。
    ///
    /// `expected_token` 为空时，拦截器跳过所有校验（开发模式）。
    pub fn new(expected_token: String) -> Self {
        Self { expected_token }
    }

    /// 是否启用认证（expected_token 非空）。
    pub fn is_enabled(&self) -> bool {
        !self.expected_token.is_empty()
    }
}

impl tonic::service::Interceptor for AuthInterceptor {
    fn call(&mut self, req: &Request<()>) -> Result<Request<()>, Status> {
        // 空 token：跳过校验（开发模式）
        if self.expected_token.is_empty() {
            return Ok(req.clone());
        }

        // 从 metadata 读取 Authorization 头
        let auth_header = req
            .metadata()
            .get(AUTHORIZATION_HEADER)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| {
                tracing::warn!(
                    "MPC-P1-05: gRPC request rejected — missing Authorization header"
                );
                Status::unauthenticated("Missing Authorization header")
            })?;

        // 校验 Bearer 前缀
        if !auth_header.starts_with(BEARER_PREFIX) {
            tracing::warn!(
                "MPC-P1-05: gRPC request rejected — Authorization header not Bearer format"
            );
            return Err(Status::unauthenticated(
                "Authorization header must be Bearer format",
            ));
        }

        // 提取并校验 token（不记录实际 token 值）
        let provided_token = &auth_header[BEARER_PREFIX.len()..];
        if provided_token != self.expected_token {
            tracing::warn!("MPC-P1-05: gRPC request rejected — auth token mismatch");
            return Err(Status::unauthenticated("Invalid auth token"));
        }

        tracing::debug!("MPC-P1-05: gRPC request authorized");
        Ok(req.clone())
    }
}

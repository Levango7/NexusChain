//! gRPC 服务端实现：实现 `MpcCryptoService` trait，
//! 将 Dkg/Sign/Aggregate/HealthCheck 四个 RPC 委托给对应模块。
//!
//! v1.9.2：接入真实 GG20 门限 ECDSA（`gg20` 模块），并与 nexus-signing-service
//! 的 Java proto 契约（`nexus.mpc` 包、hex 字符串编码、HealthCheck）完全对齐。
//!
//! 部署模型（诚实声明）：可信协调器模型。引擎进程内缓存 DKG 会话与签名运行，
//! Sign RPC 首次调用在进程内执行全部签名方 GG20 协议并缓存，后续调用取份额；
//! Aggregate RPC 返回已验证的最终签名。门限密码学数学真实（Paillier/Feldman VSS/
//! MtA/ZK），产出可被标准 secp256k1 验证的签名；各方份额暂驻留进程内存，
//! 完全分散式部署为后续演进目标。

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
use crate::sign;

/// gRPC 服务实现体。
///
/// 持有两级缓存：
///   * `sessions`：session_id -> DKG 会话（各方密钥材料）
///   * `sign_runs`：session_id -> 一次完整 GG20 签名运行结果
///
/// 注：不派生 Debug，因缓存内含第三方密码学库类型，未必实现 Debug。
pub struct MpcCryptoServiceImpl {
    pub sessions: Mutex<HashMap<String, DkgSession>>,
    pub sign_runs: Mutex<HashMap<String, SignCache>>,
}

impl Default for MpcCryptoServiceImpl {
    fn default() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            sign_runs: Mutex::new(HashMap::new()),
        }
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
            "rpc Dkg (MPC-P1-05: caller identity logged)"
        );
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
            "rpc Sign (MPC-P1-05: caller identity logged)"
        );
        let resp = sign::run_sign(&self.sessions, &self.sign_runs, req)
            .map_err(|e| Status::internal(format!("sign: {e}")))?;
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

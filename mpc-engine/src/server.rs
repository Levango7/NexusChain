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
        let req = req.into_inner();
        tracing::info!(
            session_id = %req.session_id,
            threshold = req.threshold,
            total_parties = req.total_parties,
            "rpc Dkg"
        );
        let resp = dkg::run_dkg(&self.sessions, req)
            .map_err(|e| Status::internal(format!("dkg: {e}")))?;
        Ok(Response::new(resp))
    }

    /// 部分签名。
    async fn sign(&self, req: Request<SignRequest>) -> Result<Response<SignResponse>, Status> {
        let req = req.into_inner();
        tracing::info!(
            session_id = %req.session_id,
            party_index = req.party_index,
            "rpc Sign"
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
        let req = req.into_inner();
        tracing::info!(
            session_id = %req.session_id,
            shares = req.partial_signatures.len(),
            "rpc Aggregate"
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

//! gRPC 服务端实现：实现 `MpcCryptoService` trait，
//! 将 Dkg/Sign/Aggregate 三个 RPC 委托给对应模块。

use tonic::{Request, Response, Status};

use crate::aggregate;
use crate::dkg;
use crate::proto::mpc_crypto::*;
use crate::sign;

/// gRPC 服务实现体。无状态——会话状态由调用方（signing-service 编排层）持有，
/// 引擎仅负责单次密码学计算。
#[derive(Default, Debug)]
pub struct MpcCryptoServiceImpl;

#[tonic::async_trait]
impl mpc_crypto_service_server::MpcCryptoService for MpcCryptoServiceImpl {
    /// 分布式密钥生成。
    async fn dkg(&self, req: Request<DkgRequest>) -> Result<Response<DkgResponse>, Status> {
        let req = req.into_inner();
        tracing::info!(
            session_id = %req.session_id,
            threshold = req.threshold,
            party_count = req.party_count,
            "rpc Dkg"
        );
        let resp = dkg::run_dkg(req)
            .await
            .map_err(|e| Status::unimplemented(format!("dkg: {e}")))?;
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
        let resp = sign::run_sign(req)
            .await
            .map_err(|e| Status::unimplemented(format!("sign: {e}")))?;
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
            shares = req.sig_shares.len(),
            "rpc Aggregate"
        );
        let resp = aggregate::run_aggregate(req)
            .await
            .map_err(|e| Status::unimplemented(format!("aggregate: {e}")))?;
        Ok(Response::new(resp))
    }

    /// 健康检查。
    async fn ping(
        &self,
        _req: Request<PingRequest>,
    ) -> Result<Response<PongResponse>, Status> {
        Ok(Response::new(PongResponse {
            version: env!("CARGO_PKG_VERSION").to_string(),
            healthy: true,
        }))
    }
}
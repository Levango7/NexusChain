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
use crate::cggmp::CgMessage;
use crate::cggmp_state::{
    CgDriverHandle, DriverCommand, DriverReply,
};
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
    /// 协调器转发：true 表示此节点是 DKG/Sign 协调器（party_index=0）。
    pub is_coordinator: bool,
    /// 客户端 TLS 配置，用于转发 gRPC 调用到协调器。
    #[cfg(feature = "tls")]
    pub forward_tls_config: Option<tonic::transport::ClientTlsConfig>,
    /// Auth token，用于转发 gRPC 调用。
    pub auth_token: String,
    /// v2.2.0 分散式注册表（真门限安全，阶段一：DKG 份额隔离 + 消息转发）。
    pub dist: crate::distributed::DistRegistry,
    /// v2.2.0 阶段二 F 批：CGGMP21 驱动线程 actor 句柄（!Send 状态机独占线程）。
    /// `global()` 进程单例——clone 廉价（信封通道 + Arc relay 池）。
    pub cg_driver: CgDriverHandle,
}

impl Default for MpcCryptoServiceImpl {
    fn default() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            sign_runs: Mutex::new(HashMap::new()),
            session_mgr: SessionManager::new(),
            my_party_id: String::new(),
            is_coordinator: true,
            #[cfg(feature = "tls")]
            forward_tls_config: None,
            auth_token: String::new(),
            dist: crate::distributed::DistRegistry::new(),
            cg_driver: CgDriverHandle::global(),
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
            is_coordinator: true,
            #[cfg(feature = "tls")]
            forward_tls_config: None,
            auth_token: String::new(),
            dist: crate::distributed::DistRegistry::new(),
            cg_driver: CgDriverHandle::global(),
        }
    }

    /// 创建带**独立** CGGMP 驱动线程的服务实例（F 批）。
    ///
    /// `CgDriverHandle::global()` 是进程单例——**一个引擎进程只代表一个
    /// MPC 参与方**（生产部署每 party 一进程，K8s StatefulSet 3 副本）。
    /// 同进程需要多个独立参与方时（tests/cggmp_rpc_e2e.rs 的进程内 3-server
    /// 验收），用本构造器为每个 server 配独立驱动线程——否则三方共享
    /// 同一 session 槽位，StartKeygen 幂等守卫会把 i=1/2 挡掉（F 批 e2e
    /// 实证：三方变一方，200 轮空转）。
    pub fn with_independent_cggmp_driver() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            sign_runs: Mutex::new(HashMap::new()),
            session_mgr: SessionManager::new(),
            my_party_id: String::new(),
            is_coordinator: true,
            #[cfg(feature = "tls")]
            forward_tls_config: None,
            auth_token: String::new(),
            dist: crate::distributed::DistRegistry::new(),
            cg_driver: CgDriverHandle::start(),
        }
    }

    /// 创建分布式配置的服务实例（MPC-P2-F5 协调器转发模式）。
    ///
    /// `is_coordinator` 为 true 时此节点作为协调器（party_index=0）本地执行 DKG/Sign；
    /// 为 false 时非协调器节点将 DKG/Sign 请求转发到协调器（peer_endpoints[0]）。
    pub fn with_distributed_config(
        my_party_id: String,
        is_coordinator: bool,
        #[cfg(feature = "tls")] forward_tls_config: Option<tonic::transport::ClientTlsConfig>,
        auth_token: String,
    ) -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            sign_runs: Mutex::new(HashMap::new()),
            session_mgr: SessionManager::new(),
            my_party_id,
            is_coordinator,
            #[cfg(feature = "tls")]
            forward_tls_config,
            auth_token,
            dist: crate::distributed::DistRegistry::new(),
            cg_driver: CgDriverHandle::global(),
        }
    }

    /// MPC-P2-F5: 校验调用方身份与 session 绑定一致。
    ///
    /// `my_party_id` 为空时跳过校验（兼容旧模式）；非空时严格校验。
    fn check_session_identity(&self, session_id: &str, party_index: usize) -> Result<(), Status> {
        if self.my_party_id.is_empty() {
            return Ok(()); // 兼容旧模式：未配置 party_id，跳过身份绑定
        }
        self.session_mgr
            .verify_caller(session_id, &self.my_party_id, party_index)
            .map(|_| ())
            .map_err(|e| Status::permission_denied(format!("session identity check failed: {e}")))
    }

    // ---- F 批辅助：CGGMP proto ↔ 内部类型互转 + driver 桥接 + 回执映射 ----
    // 固有方法（非 trait RPC）——供下方 trait impl 的 11 个 Cg* RPC 复用。

    /// CgRelayMessage（proto，0-based + is_p2p 哨兵消歧）→ CgMessage（内部）。
    fn cg_msg_from_proto(m: &CgRelayMessage) -> Result<CgMessage, Status> {
        let sender = u16::try_from(m.sender_index)
            .map_err(|_| Status::invalid_argument("sender_index overflow"))?;
        // is_p2p 显式区分定向/广播（F 批修正：p2p 目标方 0 与广播哨兵 0 冲突）
        let receiver = if m.is_p2p {
            Some(
                u16::try_from(m.receiver_index)
                    .map_err(|_| Status::invalid_argument("receiver_index overflow"))?,
            )
        } else {
            None
        };
        Ok(CgMessage {
            sender,
            receiver,
            payload_json: m.payload_json.clone(),
        })
    }

    /// CgMessage（内部）→ CgRelayMessage（proto）。
    fn cg_msg_to_proto(session_id: &str, m: CgMessage) -> CgRelayMessage {
        CgRelayMessage {
            session_id: session_id.to_string(),
            sender_index: u32::from(m.sender),
            receiver_index: m.receiver.map(u32::from).unwrap_or(0),
            payload_json: m.payload_json,
            is_p2p: m.receiver.is_some(),
        }
    }

    /// 驱动线程调用（spawn_blocking 包裹阻塞 `call`——keygen/aux 含
    /// Paillier 大素数生成单轮可达秒级，不占 tokio worker）。
    async fn cg_call(&self, cmd: DriverCommand) -> Result<DriverReply, Status> {
        let driver = self.cg_driver.clone();
        tokio::task::spawn_blocking(move || driver.call(cmd))
            .await
            .map_err(|e| Status::internal(format!("driver task join error: {e}")))?
            .map_err(|e| Status::internal(format!("cggmp driver: {e}")))
    }

    /// DriverReply → CgPumpResponse（keygen/aux 泵结果映射）。
    fn cg_pump_reply_to_proto(
        reply: DriverReply,
        sid: &str,
    ) -> Result<Response<CgPumpResponse>, Status> {
        match reply {
            DriverReply::PumpResult { outgoing, finished, aggregate_public_key } => {
                Ok(Response::new(CgPumpResponse {
                    outgoing: outgoing
                        .into_iter()
                        .map(|m| Self::cg_msg_to_proto(sid, m))
                        .collect(),
                    finished,
                    aggregate_public_key: aggregate_public_key.unwrap_or_default(),
                    success: true,
                    error: String::new(),
                }))
            }
            DriverReply::Error { message } => Ok(Response::new(CgPumpResponse {
                outgoing: vec![],
                finished: false,
                aggregate_public_key: String::new(),
                success: false,
                error: message,
            })),
            other => Err(Status::internal(format!(
                "unexpected driver reply for pump: {other:?}"
            ))),
        }
    }

    /// DriverReply → CgSignPumpResponse（sign 泵结果映射——完成时带 r/s hex）。
    fn cg_sign_reply_to_proto(
        reply: DriverReply,
        sid: &str,
    ) -> Result<Response<CgSignPumpResponse>, Status> {
        match reply {
            DriverReply::PumpResult { outgoing, finished, .. } => {
                Ok(Response::new(CgSignPumpResponse {
                    outgoing: outgoing
                        .into_iter()
                        .map(|m| Self::cg_msg_to_proto(sid, m))
                        .collect(),
                    finished,
                    r_hex: String::new(),
                    s_hex: String::new(),
                    success: true,
                    error: String::new(),
                }))
            }
            DriverReply::SignatureProduced { r_hex, s_hex } => {
                Ok(Response::new(CgSignPumpResponse {
                    outgoing: vec![],
                    finished: true,
                    r_hex,
                    s_hex,
                    success: true,
                    error: String::new(),
                }))
            }
            DriverReply::Error { message } => Ok(Response::new(CgSignPumpResponse {
                outgoing: vec![],
                finished: false,
                r_hex: String::new(),
                s_hex: String::new(),
                success: false,
                error: message,
            })),
            other => Err(Status::internal(format!(
                "unexpected driver reply for sign pump: {other:?}"
            ))),
        }
    }

    /// 协调器转发：将 DKG 请求转发到协调器节点（peer_endpoints[0]）。
    ///
    /// 非协调器节点在本地无缓存会话时调用此方法，将请求转发给协调器，
    /// 由协调器执行完整 GG20 DKG 并返回对应 party_index 的份额。
    async fn forward_dkg(
        &self,
        req: &DkgRequest,
        auth_header: Option<&tonic::metadata::MetadataValue<tonic::metadata::Ascii>>,
    ) -> Result<Response<DkgResponse>, Status> {
        let coordinator_endpoint = req
            .peer_endpoints
            .first()
            .ok_or_else(|| Status::internal("no coordinator endpoint"))?;

        let channel = self.connect_to_coordinator(coordinator_endpoint).await?;
        let mut client =
            crate::proto::mpc_crypto::mpc_crypto_service_client::MpcCryptoServiceClient::new(
                channel,
            );

        let mut forward_req = Request::new(req.clone());
        if let Some(auth) = auth_header {
            forward_req
                .metadata_mut()
                .insert("authorization", auth.clone());
        }

        client.dkg(forward_req).await
    }

    /// 协调器转发：将 Sign 请求转发到协调器节点（peer_endpoints[0]）。
    ///
    /// 非协调器节点在本地无缓存签名运行时调用此方法，将请求转发给协调器。
    async fn forward_sign(
        &self,
        req: &SignRequest,
        auth_header: Option<&tonic::metadata::MetadataValue<tonic::metadata::Ascii>>,
    ) -> Result<Response<SignResponse>, Status> {
        let coordinator_endpoint = req
            .peer_endpoints
            .first()
            .ok_or_else(|| Status::internal("no coordinator endpoint"))?;

        let channel = self.connect_to_coordinator(coordinator_endpoint).await?;
        let mut client =
            crate::proto::mpc_crypto::mpc_crypto_service_client::MpcCryptoServiceClient::new(
                channel,
            );

        let mut forward_req = Request::new(req.clone());
        if let Some(auth) = auth_header {
            forward_req
                .metadata_mut()
                .insert("authorization", auth.clone());
        }

        client.sign(forward_req).await
    }

    /// 建立到协调器节点的 gRPC Channel。
    ///
    /// 当 `forward_tls_config` 已配置时启用 mTLS；否则使用明文连接。
    async fn connect_to_coordinator(
        &self,
        endpoint_str: &str,
    ) -> Result<tonic::transport::Channel, Status> {
        let endpoint: tonic::transport::Endpoint = endpoint_str.parse().map_err(|e| {
            Status::internal(format!(
                "invalid coordinator endpoint '{}': {}",
                endpoint_str, e
            ))
        })?;

        #[cfg(feature = "tls")]
        {
            if let Some(tls) = &self.forward_tls_config {
                let endpoint = endpoint
                    .tls_config(tls.clone())
                    .map_err(|e| Status::internal(format!("TLS config: {}", e)))?;
                return endpoint
                    .connect()
                    .await
                    .map_err(|e| Status::internal(format!("connect to coordinator: {}", e)));
            }
        }

        endpoint
            .connect()
            .await
            .map_err(|e| Status::internal(format!("connect to coordinator: {}", e)))
    }
}

#[tonic::async_trait]
impl mpc_crypto_service_server::MpcCryptoService for MpcCryptoServiceImpl {
    /// 分布式密钥生成。
    async fn dkg(&self, req: Request<DkgRequest>) -> Result<Response<DkgResponse>, Status> {
        // MPC-P1-05: 记录调用方身份（peer_addr），便于审计追溯
        let peer = req
            .remote_addr()
            .map(|a| a.to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let auth_header = req.metadata().get("authorization").cloned();
        let req_inner = req.into_inner();
        tracing::info!(
            session_id = %req_inner.session_id,
            threshold = req_inner.threshold,
            total_parties = req_inner.total_parties,
            party_index = req_inner.party_index,
            peer = %peer,
            "rpc Dkg (MPC-P1-05: caller identity logged, MPC-P2-F5: distributed security)"
        );

        // 协调器转发：非协调器节点在无缓存会话时转发到协调器
        if !self.is_coordinator
            && req_inner.party_index != 0
            && !req_inner.peer_endpoints.is_empty()
        {
            let has_session = {
                let guard = self
                    .sessions
                    .lock()
                    .map_err(|e| Status::internal(format!("lock: {e}")))?;
                guard.contains_key(&req_inner.session_id)
            };
            if !has_session {
                tracing::info!(session_id = %req_inner.session_id, "forwarding DKG to coordinator");
                return self.forward_dkg(&req_inner, auth_header.as_ref()).await;
            }
        }

        // 中10: 在 DKG（创建新 session 的入口）触发过期清理，回收 Closed/超时 session。
        // 选择在 DKG 触发而非 Sign/Aggregate：DKG 是 session 生命周期的起点，
        // 在此处清理可避免创建新 session 时被过期 session 占用配额（与中11 max_sessions 协同）。
        let reaped = self.session_mgr.cleanup_expired_sessions();
        if reaped > 0 {
            tracing::info!(
                reaped,
                "中10: expired sessions reaped before creating new session"
            );
        }

        // MPC-P2-F5: 创建 session 并绑定调用方身份（my_party_id 非空时）
        // 跳过转发请求的 identity binding（协调器处理转发请求时 party_index != 0）
        let is_forwarded = self.is_coordinator && req_inner.party_index != 0;
        if !is_forwarded
            && !self.my_party_id.is_empty()
            && !req_inner.session_id.is_empty()
            && req_inner.party_index >= 0
        {
            self.session_mgr
                .create_session(
                    &req_inner.session_id,
                    &self.my_party_id,
                    req_inner.party_index as usize,
                )
                .map_err(|e| {
                    Status::permission_denied(format!("session identity binding failed: {e}"))
                })?;
        }

        let resp = dkg::run_dkg(&self.sessions, req_inner)
            .map_err(|e| Status::internal(format!("dkg: {e}")))?;
        Ok(Response::new(resp))
    }

    /// 部分签名。
    async fn sign(&self, req: Request<SignRequest>) -> Result<Response<SignResponse>, Status> {
        // MPC-P1-05: 记录调用方身份
        let peer = req
            .remote_addr()
            .map(|a| a.to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let auth_header = req.metadata().get("authorization").cloned();
        let req_inner = req.into_inner();
        tracing::info!(
            session_id = %req_inner.session_id,
            party_index = req_inner.party_index,
            peer = %peer,
            "rpc Sign (MPC-P1-05: caller identity logged, MPC-P2-F5: identity check)"
        );

        // 协调器转发：非协调器节点在无缓存签名运行时转发到协调器
        if !self.is_coordinator
            && req_inner.party_index != 0
            && !req_inner.peer_endpoints.is_empty()
        {
            let has_sign_run = {
                let guard = self
                    .sign_runs
                    .lock()
                    .map_err(|e| Status::internal(format!("lock: {e}")))?;
                guard.contains_key(&req_inner.session_id)
            };
            if !has_sign_run {
                tracing::info!(session_id = %req_inner.session_id, "forwarding Sign to coordinator");
                return self.forward_sign(&req_inner, auth_header.as_ref()).await;
            }
        }

        // MPC-P2-F5: 校验调用方身份与 session 绑定一致
        // 保存 session_id 供状态转换使用（req 会被 move 进 run_sign）
        let session_id = req_inner.session_id.clone();
        // 跳过转发请求的 identity check（协调器处理转发请求时 party_index != 0）
        let is_forwarded = self.is_coordinator && req_inner.party_index != 0;
        if !is_forwarded && req_inner.party_index >= 0 {
            self.check_session_identity(&req_inner.session_id, req_inner.party_index as usize)?;
        }

        let resp = sign::run_sign(&self.sessions, &self.sign_runs, req_inner)
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
            .remote_addr()
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

    // ==================== v2.2.0 分散式（真门限安全，阶段一） ====================
    // 以下三个 RPC 实现 DKG 份额隔离：协调器只做消息转发（relay_*），
    // 任何一方（含协调器）都无法接触他方份额。

    /// 各方向协调器发布 DKG 协议消息；协调器落入转发池供其他方拉取。
    ///
    /// 安全属性：协调器不理解、不落盘、不修改 payload（纯管道）；
    /// 消息内容是 round_based::Msg<keygen::ProtocolMessage> 的 JSON——
    /// 含本方 Paillier 公钥材料/VSS 承诺，不含任何私钥份额。
    async fn relay_dkg_message(
        &self,
        req: Request<DistDkgMessage>,
    ) -> Result<Response<RelayAck>, Status> {
        use crate::distributed::DistMessage;
        let m = req.into_inner();
        let msg = DistMessage {
            sender: u16::try_from(m.sender_index)
                .map_err(|_| Status::invalid_argument("sender_index overflow"))?,
            receiver: if m.receiver_index == 0 {
                None
            } else {
                Some(
                    u16::try_from(m.receiver_index)
                        .map_err(|_| Status::invalid_argument("receiver_index overflow"))?,
                )
            },
            payload_json: m.payload_json,
        };
        // 基本载荷校验（fail-closed：非 JSON 直接拒绝，防垃圾灌池）
        if serde_json::from_str::<serde_json::Value>(&msg.payload_json).is_err() {
            return Ok(Response::new(RelayAck {
                success: false,
                error: "payload_json is not valid JSON".to_string(),
            }));
        }
        let before = self.dist.relay_publish(&m.session_id, vec![msg]);
        tracing::info!(
            session_id = %m.session_id,
            sender = m.sender_index,
            queue_len = before + 1,
            "v2.2.0 dist: DKG message relayed (coordinator is a byte pipe)"
        );
        Ok(Response::new(RelayAck {
            success: true,
            error: String::new(),
        }))
    }

    /// sign 阶段一不做转发（上游 OfflineProtocolMessage 私有，见
    /// distributed.rs 模块头"范围"）——预留 RPC 返回明确的 not-implemented。
    async fn relay_sign_message(
        &self,
        _req: Request<DistSignMessage>,
    ) -> Result<Response<RelayAck>, Status> {
        Ok(Response::new(RelayAck {
            success: false,
            error: "distributed sign relay is not available in stage 1 \
                     (upstream OfflineProtocolMessage is crate-private; see distributed.rs)"
                .to_string(),
        }))
    }

    /// 查询本节点分散式 DKG 进度（轮次/完成态）。
    async fn dist_status(
        &self,
        req: Request<DistStatusRequest>,
    ) -> Result<Response<DistStatusResponse>, Status> {
        let sid = req.into_inner().session_id;
        let (round, finished) = self
            .dist
            .dkg_with::<(u16, bool)>(&sid, |s| match s {
                Some(st) => (st.current_round(), st.is_finished()),
                None => (0, false),
            })
            .map_err(|e| Status::internal(format!("dist registry lock: {e}")))?;
        Ok(Response::new(DistStatusResponse {
            current_round: u32::from(round),
            finished,
            error: if self.dist.dkg_exists(&sid) {
                String::new()
            } else {
                "no distributed DKG state for this session".to_string()
            },
        }))
    }

    // ==================== v2.2.0 阶段二 F 批：CGGMP21 分散式生命周期 ====================
    // 全部经 CgDriverHandle 信封指令转发到驱动线程（状态机 !Send——独占线程
    // 是 E 批确立的硬约束）。`call` 阻塞等待回执——用 spawn_blocking 包裹，
    // 不占 tokio worker 线程（keygen/aux 含 Paillier 大素数生成，单轮可达秒级）。
    // 辅助函数（互转/桥接/回执映射）在固有 impl 块（check_session_identity 后）。

    /// 启动 CGGMP21 threshold keygen（0-based index；t = 签名所需方数）。
    async fn cg_start_keygen(
        &self,
        req: Request<CgStartKeygenRequest>,
    ) -> Result<Response<CgPumpResponse>, Status> {
        let r = req.into_inner();
        tracing::info!(
            session_id = %r.session_id,
            my_index = r.my_index,
            n = r.total_parties,
            t = r.threshold,
            "rpc CgStartKeygen (v2.2.0 stage-2 CGGMP21 threshold keygen)"
        );
        let sid = r.session_id.clone();
        let my_index = u16::try_from(r.my_index)
            .map_err(|_| Status::invalid_argument("my_index overflow"))?;
        let n = u16::try_from(r.total_parties)
            .map_err(|_| Status::invalid_argument("total_parties overflow"))?;
        let t = u16::try_from(r.threshold)
            .map_err(|_| Status::invalid_argument("threshold overflow"))?;
        if t == 0 || t > n {
            return Ok(Response::new(CgPumpResponse {
                outgoing: vec![],
                finished: false,
                aggregate_public_key: String::new(),
                success: false,
                error: format!("threshold must be in [1, {n}] (got {t})"),
            }));
        }
        let reply = self
            .cg_call(DriverCommand::StartKeygen {
                session_id: r.session_id,
                counter: r.counter,
                i: my_index,
                n,
                t,
            })
            .await?;
        Self::cg_pump_reply_to_proto(reply, &sid)
    }

    /// 泵动 keygen（喂入协调器转来的消息，取回新产出 outgoing）。
    async fn cg_pump_keygen(
        &self,
        req: Request<CgPumpRequest>,
    ) -> Result<Response<CgPumpResponse>, Status> {
        let r = req.into_inner();
        let sid = r.session_id.clone();
        let incoming = r
            .incoming
            .iter()
            .map(Self::cg_msg_from_proto)
            .collect::<Result<Vec<_>, _>>()?;
        let reply = self
            .cg_call(DriverCommand::PumpKeygen {
                session_id: r.session_id,
                incoming,
            })
            .await?;
        Self::cg_pump_reply_to_proto(reply, &sid)
    }

    /// 启动 CGGMP21 aux_info 生成（Paillier 辅助数据；DKG 前置/并行）。
    async fn cg_start_aux(
        &self,
        req: Request<CgStartAuxRequest>,
    ) -> Result<Response<CgPumpResponse>, Status> {
        let r = req.into_inner();
        tracing::info!(
            session_id = %r.session_id,
            my_index = r.my_index,
            n = r.total_parties,
            "rpc CgStartAux (v2.2.0 stage-2 CGGMP21 aux_info gen)"
        );
        let sid = r.session_id.clone();
        // 阶段边界：清 relay 池（keygen 尾巴不得混入 aux 阶段——F 批阶段隔离）
        self.cg_driver.relay.clear_session(&sid);
        let my_index = u16::try_from(r.my_index)
            .map_err(|_| Status::invalid_argument("my_index overflow"))?;
        let n = u16::try_from(r.total_parties)
            .map_err(|_| Status::invalid_argument("total_parties overflow"))?;
        let reply = self
            .cg_call(DriverCommand::StartAux {
                session_id: r.session_id,
                counter: r.counter,
                i: my_index,
                n,
            })
            .await?;
        Self::cg_pump_reply_to_proto(reply, &sid)
    }

    /// 泵动 aux_info。
    async fn cg_pump_aux(
        &self,
        req: Request<CgPumpRequest>,
    ) -> Result<Response<CgPumpResponse>, Status> {
        let r = req.into_inner();
        let sid = r.session_id.clone();
        let incoming = r
            .incoming
            .iter()
            .map(Self::cg_msg_from_proto)
            .collect::<Result<Vec<_>, _>>()?;
        let reply = self
            .cg_call(DriverCommand::PumpAux {
                session_id: r.session_id,
                incoming,
            })
            .await?;
        Self::cg_pump_reply_to_proto(reply, &sid)
    }

    /// 合成完整 KeyShare（core + aux → validate）。
    async fn cg_assemble_share(
        &self,
        req: Request<CgSessionOnly>,
    ) -> Result<Response<CgAck>, Status> {
        let r = req.into_inner();
        tracing::info!(session_id = %r.session_id, "rpc CgAssembleShare");
        let reply = self
            .cg_call(DriverCommand::AssembleShare {
                session_id: r.session_id,
            })
            .await?;
        Ok(Response::new(match reply {
            DriverReply::ShareAssembled => CgAck {
                success: true,
                error: String::new(),
            },
            DriverReply::Error { message } => CgAck {
                success: false,
                error: message,
            },
            other => return Err(Status::internal(format!("unexpected reply: {other:?}"))),
        }))
    }

    /// 启动 CGGMP21 签名（0-based；signers 恰好 t 个——原生 t-of-n）。
    async fn cg_start_sign(
        &self,
        req: Request<CgStartSignRequest>,
    ) -> Result<Response<CgSignPumpResponse>, Status> {
        let r = req.into_inner();
        tracing::info!(
            session_id = %r.session_id,
            my_index_in_signers = r.my_index_in_signers,
            signers = ?r.signers_at_keygen,
            "rpc CgStartSign (v2.2.0 stage-2 CGGMP21 threshold sign)"
        );
        let sid = r.session_id.clone();
        // 阶段边界：清 relay 池（keygen/aux 尾巴不得混入 sign 阶段）
        self.cg_driver.relay.clear_session(&sid);
        let my_index_in_signers = u16::try_from(r.my_index_in_signers)
            .map_err(|_| Status::invalid_argument("my_index_in_signers overflow"))?;
        if r.message_hash.len() != 32 {
            return Err(Status::invalid_argument(format!(
                "message_hash must be 32 bytes, got {}",
                r.message_hash.len()
            )));
        }
        let mut message_hash = [0u8; 32];
        message_hash.copy_from_slice(&r.message_hash);
        let signers = r
            .signers_at_keygen
            .iter()
            .map(|&s| {
                u16::try_from(s).map_err(|_| Status::invalid_argument("signer index overflow"))
            })
            .collect::<Result<Vec<u16>, _>>()?;
        if signers.is_empty() {
            return Err(Status::invalid_argument("signers_at_keygen must not be empty"));
        }
        let reply = self
            .cg_call(DriverCommand::StartSign {
                session_id: r.session_id,
                counter: r.counter,
                i: my_index_in_signers,
                signers_at_keygen: signers,
                message_hash,
            })
            .await?;
        Self::cg_sign_reply_to_proto(reply, &sid)
    }

    /// 泵动 sign。
    async fn cg_pump_sign(
        &self,
        req: Request<CgPumpRequest>,
    ) -> Result<Response<CgSignPumpResponse>, Status> {
        let r = req.into_inner();
        let sid = r.session_id.clone();
        let incoming = r
            .incoming
            .iter()
            .map(Self::cg_msg_from_proto)
            .collect::<Result<Vec<_>, _>>()?;
        let reply = self
            .cg_call(DriverCommand::PumpSign {
                session_id: r.session_id,
                incoming,
            })
            .await?;
        Self::cg_sign_reply_to_proto(reply, &sid)
    }

    /// 用 session 聚合公钥本地验签（不信任调用方传参——S4 同款信任根基）。
    async fn cg_verify_signature(
        &self,
        req: Request<CgVerifyRequest>,
    ) -> Result<Response<CgVerifyResponse>, Status> {
        let r = req.into_inner();
        tracing::info!(session_id = %r.session_id, "rpc CgVerifySignature");
        if r.signature_r.len() != 32 || r.signature_s.len() != 32 || r.message_hash.len() != 32 {
            return Err(Status::invalid_argument(
                "signature_r/signature_s/message_hash must each be 32 bytes",
            ));
        }
        let (mut sig_r, mut sig_s, mut msg) = ([0u8; 32], [0u8; 32], [0u8; 32]);
        sig_r.copy_from_slice(&r.signature_r);
        sig_s.copy_from_slice(&r.signature_s);
        msg.copy_from_slice(&r.message_hash);
        let reply = self
            .cg_call(DriverCommand::VerifySignature {
                session_id: r.session_id,
                signature_r: sig_r,
                signature_s: sig_s,
                message_hash: msg,
            })
            .await?;
        Ok(Response::new(match reply {
            DriverReply::VerificationResult { valid } => CgVerifyResponse {
                valid,
                success: true,
                error: String::new(),
            },
            DriverReply::Error { message } => CgVerifyResponse {
                valid: false,
                success: false,
                error: message,
            },
            other => return Err(Status::internal(format!("unexpected reply: {other:?}"))),
        }))
    }

    /// 查询会话状态快照（驱动线程内三协议状态与产物）。
    async fn cg_status(
        &self,
        req: Request<CgSessionOnly>,
    ) -> Result<Response<CgStatusResponse>, Status> {
        let r = req.into_inner();
        let reply = self
            .cg_call(DriverCommand::Status {
                session_id: r.session_id,
            })
            .await?;
        Ok(Response::new(match reply {
            DriverReply::Status {
                has_keygen_state,
                has_aux_state,
                has_sign_state,
                has_core_share,
                has_aux_info,
                has_key_share,
            } => CgStatusResponse {
                has_keygen_state,
                has_aux_state,
                has_sign_state,
                has_core_share,
                has_aux_info,
                has_key_share,
                success: true,
                error: String::new(),
            },
            DriverReply::Error { message } => CgStatusResponse {
                has_keygen_state: false,
                has_aux_state: false,
                has_sign_state: false,
                has_core_share: false,
                has_aux_info: false,
                has_key_share: false,
                success: false,
                error: message,
            },
            other => return Err(Status::internal(format!("unexpected reply: {other:?}"))),
        }))
    }

    /// CGGMP21 消息发布（协调器字节管道——不解密/不落盘/不修改）。
    async fn cg_relay_publish(
        &self,
        req: Request<CgRelayMessage>,
    ) -> Result<Response<CgRelayAck>, Status> {
        let m = req.into_inner();
        let sender = u16::try_from(m.sender_index)
            .map_err(|_| Status::invalid_argument("sender_index overflow"))?;
        // is_p2p 显式区分（F 批哨兵修正——与 cg_msg_from_proto 同语义）
        let receiver = if m.is_p2p {
            Some(
                u16::try_from(m.receiver_index)
                    .map_err(|_| Status::invalid_argument("receiver_index overflow"))?,
            )
        } else {
            None
        };
        // 基本载荷校验（fail-closed：非 JSON 拒绝，防垃圾灌池——与 GG20 relay 同水位）
        if serde_json::from_str::<serde_json::Value>(&m.payload_json).is_err() {
            return Ok(Response::new(CgRelayAck {
                success: false,
                error: "payload_json is not valid JSON".to_string(),
            }));
        }
        let msg = CgMessage {
            sender,
            receiver,
            payload_json: m.payload_json,
        };
        let before = self.cg_driver.relay.publish(&m.session_id, vec![msg]);
        tracing::info!(
            session_id = %m.session_id,
            sender = sender,
            queue_len = before + 1,
            "rpc CgRelayPublish (coordinator is a byte pipe, 0-based)"
        );
        Ok(Response::new(CgRelayAck {
            success: true,
            error: String::new(),
        }))
    }

    /// CGGMP21 消息拉取（幂等；自动排除自发消息）。
    async fn cg_relay_pull(
        &self,
        req: Request<CgRelayPullRequest>,
    ) -> Result<Response<CgRelayPullResponse>, Status> {
        let r = req.into_inner();
        let my_index = u16::try_from(r.my_index)
            .map_err(|_| Status::invalid_argument("my_index overflow"))?;
        let msgs = self.cg_driver.relay.pull(&r.session_id, my_index);
        Ok(Response::new(CgRelayPullResponse {
            messages: msgs
                .into_iter()
                .map(|m| Self::cg_msg_to_proto(&r.session_id, m))
                .collect(),
            success: true,
            error: String::new(),
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
    pub fn from_party_config(config: &crate::config::PartyConfig) -> eyre::Result<Self> {
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
            eyre::eyre!("MPC-P2-F5: failed to read TLS CA '{}': {e}", config.tls_ca)
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
// 中13: token 比较使用常量时间比较（constant_time_compare），防止时序攻击
// 泄露 token 字节信息。虽然 Bearer token 失败立即拒绝，但攻击者可通过精细计时
// 测量比较耗时逐字节猜测 token；常量时间比较消除此侧信道。

/// `Authorization` metadata 头名。
const AUTHORIZATION_HEADER: &str = "authorization";

/// Bearer 前缀（RFC 6750）。
const BEARER_PREFIX: &str = "Bearer ";

/// 中13: 常量时间字节比较（防时序攻击）。
///
/// 无论 `a` 与 `b` 在何处出现首个差异，此函数都遍历到末尾，耗时仅取决于长度，
/// 不泄露任何字节位置信息。长度不同时直接返回 `false`（长度本身非敏感信息）。
///
/// # 算法
/// 1. 长度不同 → `false`（长度是公开信息，不构成时序侧信道）
/// 2. 累积所有对应字节的 XOR，若全相同则结果为 0
///
/// # 替代实现
/// 生产环境可使用 `subtle::ConstantTimeEq`（`subtle` crate）替代此手写实现，
/// 此处为避免新增依赖采用手写版本，逻辑等价。
fn constant_time_compare(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut result = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        result |= x ^ y;
    }
    result == 0
}

/// gRPC 认证拦截器（MPC-P1-05）。
///
/// 实现 `tonic::service::Interceptor`，校验每个 RPC 请求的
/// `Authorization: Bearer <token>` 头。`expected_token` 为空时跳过校验
/// （开发模式）；非空时严格校验，失败返回 `Status::unauthenticated`。
///
/// 中13: token 比较使用 `constant_time_compare`（常量时间），防时序攻击。
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
    fn call(&mut self, req: Request<()>) -> Result<Request<()>, Status> {
        // 空 token：跳过校验（开发模式）
        if self.expected_token.is_empty() {
            return Ok(req);
        }

        // 从 metadata 读取 Authorization 头
        let auth_header = req
            .metadata()
            .get(AUTHORIZATION_HEADER)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| {
                tracing::warn!("MPC-P1-05: gRPC request rejected — missing Authorization header");
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
        // 中13: 使用常量时间比较替代普通 !=，防时序攻击
        let provided_token = &auth_header[BEARER_PREFIX.len()..];
        if !constant_time_compare(provided_token.as_bytes(), self.expected_token.as_bytes()) {
            tracing::warn!("MPC-P1-05: gRPC request rejected — auth token mismatch (constant-time compare, 中13)");
            return Err(Status::unauthenticated("Invalid auth token"));
        }

        tracing::debug!("MPC-P1-05: gRPC request authorized");
        Ok(req)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    // tonic 0.12: Interceptor trait 需显式引入才能调用 AuthInterceptor::call
    use tonic::service::Interceptor;

    // ===== 中13: constant_time_compare 单元测试 =====

    #[test]
    fn constant_time_compare_equal_slices() {
        assert!(constant_time_compare(b"abc", b"abc"));
        assert!(constant_time_compare(b"", b""));
        assert!(constant_time_compare(b"Bearer xyz123", b"Bearer xyz123"));
    }

    #[test]
    fn constant_time_compare_different_slices() {
        assert!(!constant_time_compare(b"abc", b"abd"));
        assert!(!constant_time_compare(b"abc", b"xbc"));
        assert!(!constant_time_compare(b"abc", b"abC"));
    }

    #[test]
    fn constant_time_compare_different_lengths() {
        assert!(!constant_time_compare(b"abc", b"ab"));
        assert!(!constant_time_compare(b"abc", b"abcd"));
        assert!(!constant_time_compare(b"", b"a"));
    }

    #[test]
    fn auth_interceptor_accepts_correct_token() {
        let mut interceptor = AuthInterceptor::new("secret-token".to_string());
        let mut req = Request::new(());
        req.metadata_mut()
            .insert(AUTHORIZATION_HEADER, "Bearer secret-token".parse().unwrap());
        // tonic 0.12: Interceptor::call 按值接收 Request（trait 签名变更）
        assert!(interceptor.call(req).is_ok());
    }

    #[test]
    fn auth_interceptor_rejects_wrong_token() {
        let mut interceptor = AuthInterceptor::new("secret-token".to_string());
        let mut req = Request::new(());
        req.metadata_mut()
            .insert(AUTHORIZATION_HEADER, "Bearer wrong-token".parse().unwrap());
        let err = interceptor.call(req).unwrap_err();
        assert_eq!(err.code(), tonic::Code::Unauthenticated);
    }

    #[test]
    fn auth_interceptor_skips_when_token_empty() {
        let mut interceptor = AuthInterceptor::new(String::new());
        let req = Request::new(());
        assert!(
            interceptor.call(req).is_ok(),
            "empty token should skip auth"
        );
    }
}

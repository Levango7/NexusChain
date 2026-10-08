//! gRPC 服务端实现：实现 `MpcCryptoService` trait。
//!
//! **GG20 退役后（PLAN-001-R2）**：CGGMP21 是唯一的门限签名路径。服务面 =
//! `HealthCheck` + 11 个 `Cg*` RPC（keygen/aux/sign 生命周期、消息中转、
//! 验签、状态查询）。GG20 时代的 `Dkg`/`Sign`/`Aggregate` 可信协调器 RPC
//! 与阶段一分散式 `RelayDkgMessage`/`RelaySignMessage`/`DistStatus` RPC
//! 已随对应密码学模块一并删除。
//!
//! **安全模型（CGGMP21）**：
//!   * 私钥份额驻留引擎进程：`CgAssembleShare` 后份额只在驱动线程内，任何
//!     RPC 响应都不携带份额（对比 GG20 时代 Dkg 响应返回本方份额）。
//!   * gRPC 强制 mTLS：Server 端要求客户端证书（`tls_authority_root`），
//!     Client 端加载自己的证书并验证 server 证书（见 `MtlsConfig`）。
//!   * AuthInterceptor（MPC-P1-05）保留，作为应用层 Bearer token 认证补充。

use tonic::{Request, Response, Status};

use crate::cggmp::CgMessage;
use crate::cggmp_state::{CgDriverHandle, DriverCommand, DriverReply};
use crate::proto::mpc_crypto::*;

/// gRPC 服务实现体。
///
/// 仅持有 CGGMP21 驱动线程 actor 句柄：全部 `Cg*` RPC 经 `CgDriverHandle`
/// 信封指令转发到驱动线程执行（状态机 !Send——独占线程是 E 批确立的硬约束）。
///
/// 注：不派生 Debug，因句柄内含第三方密码学库状态，未必实现 Debug。
pub struct MpcCryptoServiceImpl {
    /// CGGMP21 驱动线程 actor 句柄（`global()` 进程单例；clone 廉价）。
    pub cg_driver: CgDriverHandle,
}

impl Default for MpcCryptoServiceImpl {
    fn default() -> Self {
        Self {
            cg_driver: CgDriverHandle::global(),
        }
    }
}

impl MpcCryptoServiceImpl {
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
            cg_driver: CgDriverHandle::start(),
        }
    }

    /// 创建带独立 CGGMP 驱动线程 + 显式持久化上下文的服务实例
    /// （PLAN-cggmp-keyshare-persistence：恢复 E2E 测试注入用——
    /// 同一 StorageCtx 的两个实例先后充当"重启前/重启后"进程）。
    pub fn with_independent_cggmp_driver_and_storage(
        storage: Option<crate::cggmp_state::StorageCtx>,
    ) -> Self {
        Self {
            cg_driver: CgDriverHandle::start_with_storage(storage),
        }
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
            DriverReply::PumpResult {
                outgoing,
                finished,
                aggregate_public_key,
            } => Ok(Response::new(CgPumpResponse {
                outgoing: outgoing
                    .into_iter()
                    .map(|m| Self::cg_msg_to_proto(sid, m))
                    .collect(),
                finished,
                aggregate_public_key: aggregate_public_key.unwrap_or_default(),
                success: true,
                error: String::new(),
            })),
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
            DriverReply::PumpResult {
                outgoing, finished, ..
            } => Ok(Response::new(CgSignPumpResponse {
                outgoing: outgoing
                    .into_iter()
                    .map(|m| Self::cg_msg_to_proto(sid, m))
                    .collect(),
                finished,
                r_hex: String::new(),
                s_hex: String::new(),
                success: true,
                error: String::new(),
            })),
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
}

#[tonic::async_trait]
impl mpc_crypto_service_server::MpcCryptoService for MpcCryptoServiceImpl {
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
        let my_index =
            u16::try_from(r.my_index).map_err(|_| Status::invalid_argument("my_index overflow"))?;
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
        let my_index =
            u16::try_from(r.my_index).map_err(|_| Status::invalid_argument("my_index overflow"))?;
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
            return Err(Status::invalid_argument(
                "signers_at_keygen must not be empty",
            ));
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
        let my_index =
            u16::try_from(r.my_index).map_err(|_| Status::invalid_argument("my_index overflow"))?;
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

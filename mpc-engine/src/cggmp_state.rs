//! CGGMP21 会话驱动层（v2.2.0 阶段二 E 批）——D 批骨架的落地实现。
//!
//! ## 为什么需要专用驱动线程（!Send 约束）
//!
//! round_based 0.4 `wrap_protocol` 状态机内部是 `Rc<RefCell<SharedState>>`
//! （上游 shared_state.rs:3），**非 Send**——不能进 `Mutex`、不能跨线程、
//! 不能进 async 上下文。cggmp.rs 的 `CgRegistry`/`CgSession` 因此只能活在
//! 单一线程内。本模块用**专用驱动线程 actor** 承载：
//!
//! ```text
//! RPC/测试层（任意线程）               驱动线程（独占 !Send 状态机）
//! ┌────────────────────────┐  信封(指令+oneshot)  ┌──────────────────────┐
//! │ CgDriverHandle::start()│ ───────────────────► │ recv 信封             │
//! │ handle.call(cmd).?     │                      │ handle → DriverReply  │
//! │                        │ ◄─────────────────── │ oneshot 回执          │
//! └────────────────────────┘                     └──────────────────────┘
//! ```
//!
//! 跨线程边界只传 owned + Send 数据（`DriverCommand`/`DriverReply`/`CgMessage`）；
//! 状态机与 `KeyShare` 本体永不离开驱动线程（需落盘时经 `encode_key_share`
//! + persistence AES 层字节化后导出——后续批接 RPC）。
//!
//! ## 三协议编排（CGGMP21 完整门限链路，单 session 生命周期）
//!
//! 1. `StartKeygen(i, n, t)` → keygen 状态机 + 首轮 pump（第 0 轮广播）
//! 2. 各方交换消息（协调器转发；传输不归本层）→ `PumpKeygen` 至完成
//!    → `core_share`（IncompleteKeyShare，含全体一致的聚合公钥）
//! 3. `StartAux(i, n)` → aux_info 状态机（Paillier 辅助数据）→ 同法驱动
//! 4. `AssembleShare`：core + aux → `validate_parts` → `from_parts` →
//!    `validate()` → 完整 `KeyShare`
//! 5. `StartSign(signers, message_hash)` → leak share/signers → sign 状态机
//!    → `PumpSign` 至完成 → `Signature`
//! 6. `VerifySignature`：聚合公钥本地验签（信任根基与分布式路径一致——
//!    审计 S4 修复同款原则：不信任调用方传入的验签公钥）
//!
//! ## 与 distributed.rs（阶段一 GG20）的关系
//!
//! 阶段一 GG20 分散式 DKG 保留（Java E2E 依赖），CGGMP21 为并行演进路径；
//! 协调器路径（sign.rs/aggregate.rs）按 Cargo.toml 规划终将退役。本模块
//! 只实现引擎内部驱动与测试验证，RPC 接线（relay_sign_message 等真实化）
//! 是下一批工作。

use std::collections::HashMap;
use std::sync::mpsc::{channel, Sender};
use std::sync::OnceLock;

use cggmp21::generic_ec::{NonZero, Point, Scalar};
use cggmp21::key_share::{Validate, ValidateFromParts};
use cggmp21::security_level::SecurityLevel128;
use cggmp21::signing::DataToSign;
use cggmp21::supported_curves::Secp256k1;
use cggmp21::{IncompleteKeyShare, KeyShare};
use sha2_010::Sha256 as CgSha;

use crate::cggmp::{
    aux_info_state_machine, keygen_state_machine, pump, sign_state_machine, CgAuxMsg, CgKeygenMsg,
    CgMessage, CgRegistry, CgSignMsg,
};

// =========================================================================
// 指令与回执（跨线程边界——全部 owned + Send）
// =========================================================================

/// RPC/测试层 → 驱动线程的指令。
#[derive(Debug)]
pub enum DriverCommand {
    /// 初始化 keygen 状态机并跑首轮（i/n/t 0-based；t 为门限——签名方数）。
    StartKeygen {
        session_id: String,
        counter: u32,
        i: u16,
        n: u16,
        t: u16,
    },
    /// 驱动 keygen（喂入协调器转来的消息）。
    PumpKeygen {
        session_id: String,
        incoming: Vec<CgMessage>,
    },
    /// 初始化 aux_info 状态机并跑首轮。
    StartAux {
        session_id: String,
        counter: u32,
        i: u16,
        n: u16,
    },
    /// 驱动 aux_info。
    PumpAux {
        session_id: String,
        incoming: Vec<CgMessage>,
    },
    /// 合成完整份额（core + aux → KeyShare）。
    AssembleShare { session_id: String },
    /// 初始化 sign 状态机并跑首轮（signers 为签名方在 keygen 时的原始索引；
    /// i 为本方在**本批签名方**中的 0-based 序号；message_hash 32 字节）。
    StartSign {
        session_id: String,
        counter: u32,
        i: u16,
        signers_at_keygen: Vec<u16>,
        message_hash: [u8; 32],
    },
    /// 驱动 sign。
    PumpSign {
        session_id: String,
        incoming: Vec<CgMessage>,
    },
    /// 用 session 聚合公钥验签（r/s 与消息哈希均为 32 字节）。
    VerifySignature {
        session_id: String,
        signature_r: [u8; 32],
        signature_s: [u8; 32],
        message_hash: [u8; 32],
    },
    /// 查询会话状态快照。
    Status { session_id: String },
}

/// 驱动线程 → 调用方的回执（owned + Send）。
#[derive(Debug)]
pub enum DriverReply {
    /// keygen/aux/sign 的 pump 结果。
    PumpResult {
        outgoing: Vec<CgMessage>,
        finished: bool,
        /// keygen 完成时的聚合公钥（压缩 SEC1 hex）——公钥材料可导出。
        aggregate_public_key: Option<String>,
    },
    /// AssembleShare 完成。
    ShareAssembled,
    /// sign 完成：签名 (r, s) 大端 hex。
    SignatureProduced { r_hex: String, s_hex: String },
    /// VerifySignature 结果。
    VerificationResult { valid: bool },
    /// 会话状态快照。
    Status {
        has_keygen_state: bool,
        has_aux_state: bool,
        has_sign_state: bool,
        has_core_share: bool,
        has_aux_info: bool,
        has_key_share: bool,
    },
    /// 指令执行失败（fail-closed，错误信息含 session 上下文）。
    Error { message: String },
}

/// 指令信封：命令 + 一次性回执通道（oneshot 语义用 std mpsc 模拟）。
struct DriverEnvelope {
    cmd: DriverCommand,
    reply_tx: Sender<DriverReply>,
}

// =========================================================================
// 驱动线程主体（独占 !Send 状态机与会话上下文）
// =========================================================================

/// 会话上下文（驱动线程内）——pump 编码 outgoing 需要本方 index。
struct SessionCtx {
    my_index: u16,
}

/// 驱动线程内部状态（全 !Send——只在本线程存活）。
struct DriverInner {
    registry: CgRegistry,
    ctxs: HashMap<String, SessionCtx>,
}

impl DriverInner {
    fn new() -> Self {
        Self {
            registry: CgRegistry::new(),
            ctxs: HashMap::new(),
        }
    }

    fn ctx_index(&self, sid: &str) -> eyre::Result<u16> {
        self.ctxs
            .get(sid)
            .map(|c| c.my_index)
            .ok_or_else(|| eyre::eyre!("no session context for {sid} (run Start* first)"))
    }

    fn err(msg: impl std::fmt::Display) -> DriverReply {
        DriverReply::Error { message: msg.to_string() }
    }

    /// 处理一条指令 → 回执。
    fn handle(&mut self, cmd: DriverCommand) -> DriverReply {
        match cmd {
            DriverCommand::StartKeygen { session_id, counter, i, n, t } => {
                self.start_keygen(&session_id, counter, i, n, t)
            }
            DriverCommand::PumpKeygen { session_id, incoming } => {
                self.pump_protocol(&session_id, incoming, Protocol::Keygen)
            }
            DriverCommand::StartAux { session_id, counter, i, n } => {
                self.start_aux(&session_id, counter, i, n)
            }
            DriverCommand::PumpAux { session_id, incoming } => {
                self.pump_protocol(&session_id, incoming, Protocol::Aux)
            }
            DriverCommand::AssembleShare { session_id } => self.assemble_share(&session_id),
            DriverCommand::StartSign { session_id, counter, i, signers_at_keygen, message_hash } => {
                self.start_sign(&session_id, counter, i, signers_at_keygen, message_hash)
            }
            DriverCommand::PumpSign { session_id, incoming } => {
                self.pump_protocol(&session_id, incoming, Protocol::Sign)
            }
            DriverCommand::VerifySignature { session_id, signature_r, signature_s, message_hash } => {
                self.verify_signature(&session_id, signature_r, signature_s, message_hash)
            }
            DriverCommand::Status { session_id } => self.status(&session_id),
        }
    }

    // ---- keygen ----

    fn start_keygen(&mut self, sid: &str, counter: u32, i: u16, n: u16, t: u16) -> DriverReply {
        let r = self.registry.with(sid, |s| {
            if s.keygen_state.is_some() {
                // 幂等：已初始化则不重建（防 eid 漂移——同 counter 才是同一执行）
                return Ok(());
            }
            s.keygen_state = Some(Box::new(keygen_state_machine(sid, counter, i, n, t)));
            Ok(())
        });
        if let Err(e) = r.flatten() {
            return Self::err(format!("start_keygen {sid}: {e}"));
        }
        self.ctxs.insert(sid.to_string(), SessionCtx { my_index: i });
        self.pump_protocol(sid, vec![], Protocol::Keygen)
    }

    // ---- aux ----

    fn start_aux(&mut self, sid: &str, counter: u32, i: u16, n: u16) -> DriverReply {
        let r = self
            .registry
            .with(sid, |s| -> eyre::Result<()> {
                if s.aux_state.is_some() {
                    return Ok(());
                }
                s.aux_state = Some(Box::new(aux_info_state_machine(sid, counter, i, n)?));
                Ok(())
            })
            .flatten();
        if let Err(e) = r {
            return Self::err(format!("start_aux {sid}: {e}"));
        }
        // aux 与 keygen 同 party 布局：沿用/建立会话上下文（StartAux 带 i，
        // 直接以指令的 i 为准——与 StartKeygen 的 insert 语义一致）
        self.ctxs.insert(sid.to_string(), SessionCtx { my_index: i });
        self.pump_protocol(sid, vec![], Protocol::Aux)
    }

    // ---- 通用泵（keygen/aux/sign 三协议同构驱动） ----

    fn pump_protocol(&mut self, sid: &str, incoming: Vec<CgMessage>, proto: Protocol) -> DriverReply {
        let my = match self.ctx_index(sid) {
            Ok(i) => i,
            Err(e) => return Self::err(format!("pump {proto:?} {sid}: {e}")),
        };
        // 三协议同一 pump——闭包按协议取状态机槽位并 pump；完成时收产物。
        // with 返回 Result<Result<...>>（外层=registry 锁，内层=协议结果）→ flatten。
        let r = self
            .registry
            .with(sid, |s| -> eyre::Result<(Vec<CgMessage>, bool, Option<ProdKind>)> {
                match proto {
                    Protocol::Keygen => {
                        let Some(sm) = s.keygen_state.as_mut() else {
                            return Err(eyre::eyre!("keygen not started for {sid}"));
                        };
                        let (out, fin, output) = pump::<_, CgKeygenMsg>(sm.as_mut(), &incoming, my)?;
                        if let Some(res) = output {
                            let core = res.map_err(|e| eyre::eyre!("keygen failed: {e:?}"))?;
                            s.core_share = Some(core);
                            s.keygen_state = None; // 完成：释放状态机
                        }
                        Ok((out, fin, None))
                    }
                    Protocol::Aux => {
                        let Some(sm) = s.aux_state.as_mut() else {
                            return Err(eyre::eyre!("aux not started for {sid}"));
                        };
                        let (out, fin, output) = pump::<_, CgAuxMsg>(sm.as_mut(), &incoming, my)?;
                        if let Some(res) = output {
                            let aux = res.map_err(|e| eyre::eyre!("aux_info failed: {e:?}"))?;
                            s.aux_info = Some(aux);
                            s.aux_state = None;
                        }
                        Ok((out, fin, None))
                    }
                    Protocol::Sign => {
                        let Some(sm) = s.sign_state.as_mut() else {
                            return Err(eyre::eyre!("sign not started for {sid}"));
                        };
                        let (out, fin, output) = pump::<_, CgSignMsg>(sm.as_mut(), &incoming, my)?;
                        if let Some(res) = output {
                            let sig = res.map_err(|e| eyre::eyre!("sign failed: {e:?}"))?;
                            let (r_hex, s_hex) = signature_to_hex(&sig);
                            s.sign_state = None;
                            return Ok((out, fin, Some(ProdKind::Signature(r_hex, s_hex))));
                        }
                        Ok((out, fin, None))
                    }
                }
            })
            .flatten();
        match r {
            Ok((outgoing, finished, prod)) => {
                // keygen 完成时导出聚合公钥（core_share 的 shared_public_key）
                let pk_hex = if finished && proto == Protocol::Keygen {
                    self.registry
                        .with(sid, |s| {
                            s.core_share
                                .as_ref()
                                .map(|c| point_hex(&c.shared_public_key))
                        })
                        .ok()
                        .flatten()
                } else {
                    None
                };
                match prod {
                    Some(ProdKind::Signature(r_hex, s_hex)) => {
                        DriverReply::SignatureProduced { r_hex, s_hex }
                    }
                    None => DriverReply::PumpResult {
                        outgoing,
                        finished,
                        aggregate_public_key: pk_hex,
                    },
                }
            }
            Err(e) => Self::err(format!("pump {proto:?} {sid}: {e}")),
        }
    }

    // ---- share 合成 ----

    fn assemble_share(&mut self, sid: &str) -> DriverReply {
        type Parts = (IncompleteKeyShare<Secp256k1>, cggmp21::key_share::AuxInfo<SecurityLevel128>);
        type Dirty = cggmp21::key_share::DirtyKeyShare<Secp256k1, SecurityLevel128>;
        let r = self
            .registry
            .with(sid, |s| -> eyre::Result<()> {
                let (Some(core), Some(aux)) = (s.core_share.clone(), s.aux_info.clone()) else {
                    return Err(eyre::eyre!(
                        "assemble_share {sid}: core_share or aux_info missing \
                         (complete keygen + aux first)"
                    ));
                };
                let parts: Parts = (core, aux);
                <Dirty as ValidateFromParts<Parts>>::validate_parts(&parts)
                    .map_err(|e| eyre::eyre!("share parts inconsistent: {e:?}"))?;
                let dirty = <Dirty as ValidateFromParts<Parts>>::from_parts(parts);
                let share: KeyShare<Secp256k1> = dirty
                    .validate()
                    .map_err(|e| eyre::eyre!("assembled share invalid: {e:?}"))?;
                s.key_share = Some(share);
                Ok(())
            })
            .flatten();
        match r {
            Ok(()) => DriverReply::ShareAssembled,
            Err(e) => Self::err(format!("assemble_share: {e}")),
        }
    }

    // ---- sign ----

    fn start_sign(
        &mut self,
        sid: &str,
        counter: u32,
        i: u16,
        signers_at_keygen: Vec<u16>,
        message_hash: [u8; 32],
    ) -> DriverReply {
        // sign 状态机借 share/signers——leak 成 'static（E 批权衡：每 session
        // ~KB 级泄漏，registry 化回收留后续批次；同 eid/RNG 的既有先例）
        let r = self
            .registry
            .with(sid, |s| -> eyre::Result<()> {
                let Some(share) = s.key_share.as_ref() else {
                    return Err(eyre::eyre!(
                        "start_sign {sid}: key_share missing (AssembleShare first)"
                    ));
                };
                let share_static: &'static KeyShare<Secp256k1> =
                    Box::leak(Box::new(share.clone()));
                let signers_static: &'static [u16] =
                    Box::leak(signers_at_keygen.clone().into_boxed_slice());
                let sm = sign_state_machine(
                    sid,
                    counter,
                    i,
                    signers_static,
                    share_static,
                    message_hash,
                )?;
                s.sign_state = Some(Box::new(sm));
                Ok(())
            })
            .flatten();
        match r {
            Ok(()) => self.pump_protocol(sid, vec![], Protocol::Sign),
            Err(e) => Self::err(format!("start_sign: {e}")),
        }
    }

    // ---- 验签 ----

    fn verify_signature(
        &mut self,
        sid: &str,
        r_bytes: [u8; 32],
        s_bytes: [u8; 32],
        message_hash: [u8; 32],
    ) -> DriverReply {
        let result = self
            .registry
            .with(sid, |s| -> eyre::Result<bool> {
                let Some(share) = s.key_share.as_ref() else {
                    return Err(eyre::eyre!(
                        "verify_signature {sid}: key_share missing (assemble first)"
                    ));
                };
                let pk = *share.core.shared_public_key;
                let sig = rebuild_signature(r_bytes, s_bytes)?;
                let data = data_to_sign(message_hash);
                match sig.verify(&pk, &data) {
                    Ok(()) => Ok(true),
                    Err(_) => Ok(false), // 验签失败是**结果**而非错误（fail-closed 于语义）
                }
            })
            .flatten();
        match result {
            Ok(valid) => DriverReply::VerificationResult { valid },
            Err(e) => Self::err(format!("verify_signature: {e}")),
        }
    }

    fn status(&mut self, sid: &str) -> DriverReply {
        // 闭包直接构造 DriverReply（非 Result）——with 外层是 Result<DriverReply>，
        // 不能 flatten（那是 Result<Result<..>> 的方法）。
        let r = self.registry.with(sid, |s| DriverReply::Status {
            has_keygen_state: s.keygen_state.is_some(),
            has_aux_state: s.aux_state.is_some(),
            has_sign_state: s.sign_state.is_some(),
            has_core_share: s.core_share.is_some(),
            has_aux_info: s.aux_info.is_some(),
            has_key_share: s.key_share.is_some(),
        });
        match r {
            Ok(st) => st,
            Err(e) => Self::err(format!("status: {e}")),
        }
    }
}

/// 三协议枚举（通用泵分流用）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Protocol {
    Keygen,
    Aux,
    Sign,
}

/// pump 产物种类（当前仅 sign 产出需跨闭包传递）。
enum ProdKind {
    Signature(String, String),
}

// =========================================================================
// 辅助：签名/公钥/数据编码（驱动线程内使用）
// =========================================================================

/// Signature → (r, s) 大端 hex。
///
/// `sig.r`/`sig.s` 是 `NonZero<Scalar>`——Deref 到 `Scalar` 后 `to_be_bytes()`
/// 返回 `EncodedScalar`，经 `as_bytes()` 取定长切片（secp256k1 恒 32 字节）。
fn signature_to_hex(sig: &cggmp21::signing::Signature<Secp256k1>) -> (String, String) {
    let r_bytes: [u8; 32] = sig
        .r
        .to_be_bytes()
        .as_bytes()
        .try_into()
        .expect("secp256k1 scalar is 32 bytes");
    let s_bytes: [u8; 32] = sig
        .s
        .to_be_bytes()
        .as_bytes()
        .try_into()
        .expect("secp256k1 scalar is 32 bytes");
    (hex::encode(r_bytes), hex::encode(s_bytes))
}

/// 压缩 SEC1 公钥 hex（`NonZero<Point>` Deref 到 `Point` 后 `to_bytes`）。
fn point_hex(p: &NonZero<Point<Secp256k1>>) -> String {
    let bytes = p.to_bytes(true);
    hex::encode(bytes.as_ref())
}

/// 从 32 字节大端重建 Signature（r/s 零值拒绝）。
fn rebuild_signature(
    r_bytes: [u8; 32],
    s_bytes: [u8; 32],
) -> eyre::Result<cggmp21::signing::Signature<Secp256k1>> {
    let r = Scalar::<Secp256k1>::from_be_bytes(r_bytes)
        .map_err(|e| eyre::eyre!("signature r invalid: {e:?}"))?;
    let s = Scalar::<Secp256k1>::from_be_bytes(s_bytes)
        .map_err(|e| eyre::eyre!("signature s invalid: {e:?}"))?;
    match (NonZero::from_scalar(r), NonZero::from_scalar(s)) {
        (Some(r), Some(s)) => Ok(cggmp21::signing::Signature::from_raw_parts(r, s)),
        _ => Err(eyre::eyre!("signature r/s must be non-zero")),
    }
}

/// 消息哈希 → DataToSign（与 cggmp::build_data_to_sign 同一 mod-order 语义）。
fn data_to_sign(message_hash: [u8; 32]) -> DataToSign<Secp256k1> {
    use sha2_010::Digest;
    let mut hasher = CgSha::new();
    hasher.update(message_hash);
    DataToSign::from_digest::<CgSha>(hasher)
}

// =========================================================================
// 驱动线程 + 对外句柄（Send + Clone）
// =========================================================================

/// 驱动线程句柄（RPC 层持有；线程内独占全部 !Send 状态机）。
///
/// `call` 阻塞直至驱动线程回执——RPC async 上下文应包
/// `tokio::task::block_in_place` 或经独立阻塞池；E 批测试直接同步调用。
#[derive(Clone)]
pub struct CgDriverHandle {
    tx: Sender<DriverEnvelope>,
}

impl CgDriverHandle {
    /// 启动驱动线程（通常进程单例，见 `global`）。
    pub fn start() -> Self {
        let (tx, rx) = channel::<DriverEnvelope>();
        std::thread::Builder::new()
            .name("cggmp-driver".to_string())
            .spawn(move || {
                let mut inner = DriverInner::new();
                while let Ok(env) = rx.recv() {
                    let reply = inner.handle(env.cmd);
                    let _ = env.reply_tx.send(reply);
                }
                tracing::info!("cggmp-driver thread exiting (all handles dropped)");
            })
            .expect("spawn cggmp-driver thread");
        Self { tx }
    }

    /// 发送指令并等待回执（阻塞当前线程）。
    pub fn call(&self, cmd: DriverCommand) -> eyre::Result<DriverReply> {
        let (reply_tx, reply_rx) = channel::<DriverReply>();
        self.tx
            .send(DriverEnvelope { cmd, reply_tx })
            .map_err(|_| eyre::eyre!("cggmp-driver thread has exited"))?;
        reply_rx
            .recv()
            .map_err(|_| eyre::eyre!("cggmp-driver thread dropped reply channel"))
    }

    /// 进程级单例句柄（server 层接入点；测试可各自 start 独立实例）。
    pub fn global() -> CgDriverHandle {
        static GLOBAL: OnceLock<CgDriverHandle> = OnceLock::new();
        GLOBAL.get_or_init(Self::start).clone()
    }
}

// =========================================================================
// 单元测试（驱动线程 actor 的最小验证）
// =========================================================================
// 端到端三方门限测试在 tests/cggmp_threshold_e2e.rs（E 批里程碑）。
#[cfg(test)]
mod tests {
    use super::*;

    /// actor 往返：StartKeygen → 首轮 PumpResult（不 panic、消息非空）。
    #[test]
    fn driver_start_keygen_first_round_produces_broadcast() {
        let h = CgDriverHandle::start();
        let reply = h
            .call(DriverCommand::StartKeygen {
                session_id: "driver-test-1".into(),
                counter: 0,
                i: 0,
                n: 3,
                t: 2,
            })
            .expect("call");
        match reply {
            DriverReply::PumpResult { outgoing, finished, aggregate_public_key } => {
                // keygen 第 0 轮是广播（Broadcast 消息 receiver=None）
                assert!(!outgoing.is_empty(), "round-0 must produce messages");
                assert!(outgoing.iter().all(|m| m.receiver.is_none()),
                    "round-0 must be broadcast");
                assert!(!finished);
                assert!(aggregate_public_key.is_none());
                // E 批 sender 修正验证：广播消息 sender 应为本方 index（0）
                assert!(outgoing.iter().all(|m| m.sender == 0));
            }
            other => panic!("expected PumpResult, got {other:?}"),
        }
    }

    /// Status 指令回快照；未知指令失败路径（pump 未 start 的协议）fail-closed。
    #[test]
    fn driver_status_and_fail_closed() {
        let h = CgDriverHandle::start();
        // 未 Start 的 pump → Error（fail-closed）
        let reply = h
            .call(DriverCommand::PumpSign {
                session_id: "never-started".into(),
                incoming: vec![],
            })
            .expect("call");
        assert!(matches!(reply, DriverReply::Error { .. }));

        let reply = h
            .call(DriverCommand::Status {
                session_id: "never-started".into(),
            })
            .expect("call");
        match reply {
            DriverReply::Status { has_keygen_state, has_key_share, .. } => {
                assert!(!has_keygen_state && !has_key_share);
            }
            other => panic!("expected Status, got {other:?}"),
        }
    }
}

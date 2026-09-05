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
use std::sync::{Arc, Mutex, OnceLock};

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

/// 份额持久化上下文（PLAN-cggmp-keyshare-persistence）。
///
/// `None` = 持久化功能关闭（`MPC_STORAGE_KEY` / `MPC_ENGINE_SESSION_DIR`
/// 未配置——WARN 一次，协议照常，与既有行为兼容）；`Some` = 写钩子 +
/// 读守卫全启用，写盘 IO 失败 fail-closed（绝不静默丢份额）。
#[derive(Clone, Debug)]
pub struct StorageCtx {
    pub base_dir: std::path::PathBuf,
    pub key: [u8; 32],
    pub key_version: u32,
}

impl StorageCtx {
    /// 从环境变量解析（生产路径：main.rs 把 PartyConfig.storage_key 同步到
    /// `MPC_STORAGE_KEY`；start-mpc-cluster.sh / K8s / 集群测试设
    /// `MPC_ENGINE_SESSION_DIR`——每节点独立的会话根目录）。
    fn from_env() -> Option<Self> {
        let base_dir = std::env::var("MPC_ENGINE_SESSION_DIR").ok()?;
        let key_hex = std::env::var("MPC_STORAGE_KEY").ok()?;
        Self::from_parts(base_dir, &key_hex)
    }

    /// 显式构造（测试注入用——绕开环境变量避免并行测试 env 竞态）。
    pub fn new(base_dir: std::path::PathBuf, key: [u8; 32], key_version: u32) -> Self {
        Self {
            base_dir,
            key,
            key_version,
        }
    }

    fn from_parts(base_dir: String, key_hex: &str) -> Option<Self> {
        if key_hex.len() != 64 {
            tracing::warn!(
                "cggmp persistence disabled: MPC_STORAGE_KEY must be 64 hex chars (got {})",
                key_hex.len()
            );
            return None;
        }
        let mut key = [0u8; 32];
        hex::decode_to_slice(key_hex, &mut key).ok()?;
        let key_version = std::env::var("MPC_STORAGE_KEY_VERSION")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(1);
        Some(Self {
            base_dir: std::path::PathBuf::from(base_dir),
            key,
            key_version,
        })
    }
}

/// 驱动线程内部状态（全 !Send——只在本线程存活）。
struct DriverInner {
    registry: CgRegistry,
    ctxs: HashMap<String, SessionCtx>,
    storage: Option<StorageCtx>,
}

impl DriverInner {
    fn new() -> Self {
        Self::with_storage(StorageCtx::from_env())
    }

    fn with_storage(storage: Option<StorageCtx>) -> Self {
        if storage.is_none() {
            tracing::warn!(
                "cggmp share persistence DISABLED (MPC_STORAGE_KEY / \
                 MPC_ENGINE_SESSION_DIR_BASE unset) — restart loses shares"
            );
        }
        Self {
            registry: CgRegistry::new(),
            ctxs: HashMap::new(),
            storage,
        }
    }

    fn ctx_index(&self, sid: &str) -> eyre::Result<u16> {
        self.ctxs
            .get(sid)
            .map(|c| c.my_index)
            .ok_or_else(|| eyre::eyre!("no session context for {sid} (run Start* first)"))
    }

    fn err(msg: impl std::fmt::Display) -> DriverReply {
        DriverReply::Error {
            message: msg.to_string(),
        }
    }

    /// 处理一条指令 → 回执。
    fn handle(&mut self, cmd: DriverCommand) -> DriverReply {
        match cmd {
            DriverCommand::StartKeygen {
                session_id,
                counter,
                i,
                n,
                t,
            } => self.start_keygen(&session_id, counter, i, n, t),
            DriverCommand::PumpKeygen {
                session_id,
                incoming,
            } => self.pump_protocol(&session_id, incoming, Protocol::Keygen),
            DriverCommand::StartAux {
                session_id,
                counter,
                i,
                n,
            } => self.start_aux(&session_id, counter, i, n),
            DriverCommand::PumpAux {
                session_id,
                incoming,
            } => self.pump_protocol(&session_id, incoming, Protocol::Aux),
            DriverCommand::AssembleShare { session_id } => self.assemble_share(&session_id),
            DriverCommand::StartSign {
                session_id,
                counter,
                i,
                signers_at_keygen,
                message_hash,
            } => self.start_sign(&session_id, counter, i, signers_at_keygen, message_hash),
            DriverCommand::PumpSign {
                session_id,
                incoming,
            } => self.pump_protocol(&session_id, incoming, Protocol::Sign),
            DriverCommand::VerifySignature {
                session_id,
                signature_r,
                signature_s,
                message_hash,
            } => self.verify_signature(&session_id, signature_r, signature_s, message_hash),
            DriverCommand::Status { session_id } => self.status(&session_id),
        }
    }

    // ---- keygen ----

    fn start_keygen(&mut self, sid: &str, counter: u32, i: u16, n: u16, t: u16) -> DriverReply {
        // 持久化读守卫（PLAN-cggmp-keyshare-persistence §3.4）：进程重启后同
        // session 重入——盘上已有产物则恢复进 registry 并幂等返回，绝不重跑
        // DKG 覆盖既有份额。解码失败 = 篡改/截断/错密钥 → fail-closed 硬错误。
        if let Some(ctx) = self.storage.clone() {
            match self.load_persisted_shares(sid, &ctx) {
                Ok(restored) => {
                    if restored {
                        // 恢复成功：finished 即回（keygen_state 保持 None——
                        // 调用方拿到 finished 直接进入下一阶段）
                        let agg = match self.registry.with(sid, |s| {
                            s.core_share
                                .as_ref()
                                .map(|c| point_hex(&c.shared_public_key))
                        }) {
                            Ok(pk) => pk,
                            Err(e) => return Self::err(format!("start_keygen {sid}: {e}")),
                        };
                        return DriverReply::PumpResult {
                            outgoing: vec![],
                            finished: true,
                            aggregate_public_key: agg,
                        };
                    }
                }
                Err(e) => return Self::err(format!("start_keygen {sid}: restore: {e}")),
            }
        }
        let r = self.registry.with(sid, |s| {
            if s.keygen_state.is_some() {
                // 幂等：已初始化则不重建（防 eid 漂移——同 counter 才是同一执行）
                return Ok(());
            }
            s.keygen_state = Some(Box::new(keygen_state_machine(sid, counter, i, n, t)));
            Ok(())
        });
        if let Err(e) = r.and_then(|inner| match inner {
            Ok(v) => Ok(v),
            Err(e) => Err(e),
        }) {
            return Self::err(format!("start_keygen {sid}: {e}"));
        }
        self.ctxs
            .insert(sid.to_string(), SessionCtx { my_index: i });
        self.pump_protocol(sid, vec![], Protocol::Keygen)
    }

    /// 从盘上恢复 CGGMP21 产物进 registry（存在才恢复；都不存在 = Ok(false)）。
    ///
    /// 恢复顺序：keyshare（终态）优先——存在即视为完成态；否则尝试
    /// incomplete（keygen 完成但 aux/assemble 未完成的中间态）。
    /// 解码失败 fail-closed；keyshare 与 incomplete 同时缺失属正常首跑。
    fn load_persisted_shares(&mut self, sid: &str, ctx: &StorageCtx) -> eyre::Result<bool> {
        let mut restored = false;
        let ks = crate::persistence::load_cggmp_blob(sid, "keyshare", &ctx.base_dir, &ctx.key)?;
        if let Some((_, plaintext)) = ks {
            let share = crate::cggmp::decode_key_share(&plaintext)
                .map_err(|e| eyre::eyre!("persisted keyshare invalid: {e}"))?;
            self.registry
                .with(sid, |s| {
                    s.key_share = Some(share);
                })
                .map_err(|e| eyre::eyre!("registry lock: {e}"))?;
            restored = true;
        }
        let inc = crate::persistence::load_cggmp_blob(sid, "incomplete", &ctx.base_dir, &ctx.key)?;
        if let Some((_, plaintext)) = inc {
            let core = crate::cggmp::decode_incomplete(&plaintext)
                .map_err(|e| eyre::eyre!("persisted incomplete share invalid: {e}"))?;
            self.registry
                .with(sid, |s| {
                    s.core_share = Some(core);
                })
                .map_err(|e| eyre::eyre!("registry lock: {e}"))?;
            restored = true;
        }
        Ok(restored)
    }

    // ---- aux ----

    fn start_aux(&mut self, sid: &str, counter: u32, i: u16, n: u16) -> DriverReply {
        // 持久化读守卫：
        //   1) keyshare 已在 registry → 完成态，aux 无意义，幂等返回 finished；
        //   2) 盘上有 keyshare.bin → 同上（进程重启后直接跳到 sign 的路径）；
        //   3) registry 缺 core_share 且盘上有 incomplete.bin → 恢复中间态
        //      （重启于 keygen 后 / aux 前），aux 照常跑，assemble 用恢复的 core。
        // 解码失败 fail-closed（见 load_persisted_shares）。
        let have_key_share = match self.registry.with(sid, |s| s.key_share.is_some()) {
            Ok(v) => v,
            Err(e) => return Self::err(format!("start_aux {sid}: {e}")),
        };
        if have_key_share {
            // registry 已有 key_share（含 StartKeygen 恢复路径回填）——完成态，
            // aux 无意义，幂等返回 finished（与盘上守卫同语义）。
            return DriverReply::PumpResult {
                outgoing: vec![],
                finished: true,
                aggregate_public_key: None,
            };
        }
        if let Some(ctx) = self.storage.clone() {
            let ks = crate::persistence::load_cggmp_blob(sid, "keyshare", &ctx.base_dir, &ctx.key);
            match ks {
                Err(e) => return Self::err(format!("start_aux {sid}: restore: {e}")),
                Ok(Some((_, plaintext))) => match crate::cggmp::decode_key_share(&plaintext) {
                    Ok(share) => {
                        if let Err(e) = self.registry.with(sid, |s| {
                            s.key_share = Some(share);
                        }) {
                            return Self::err(format!("start_aux {sid}: {e}"));
                        }
                        return DriverReply::PumpResult {
                            outgoing: vec![],
                            finished: true,
                            aggregate_public_key: None,
                        };
                    }
                    Err(e) => {
                        return Self::err(format!(
                            "start_aux {sid}: persisted keyshare invalid: {e}"
                        ))
                    }
                },
                Ok(None) => {}
            }
            // 无 keyshare：恢复 incomplete（若有）——assemble 需要 core_share
            if let Err(e) = self.load_persisted_shares(sid, &ctx) {
                return Self::err(format!("start_aux {sid}: restore: {e}"));
            }
        }
        let r = self
            .registry
            .with(sid, |s| -> eyre::Result<()> {
                if s.aux_state.is_some() {
                    return Ok(());
                }
                s.aux_state = Some(Box::new(aux_info_state_machine(sid, counter, i, n)?));
                Ok(())
            })
            .and_then(|inner| inner);
        if let Err(e) = r {
            return Self::err(format!("start_aux {sid}: {e}"));
        }
        // aux 与 keygen 同 party 布局：沿用/建立会话上下文（StartAux 带 i，
        // 直接以指令的 i 为准——与 StartKeygen 的 insert 语义一致）
        self.ctxs
            .insert(sid.to_string(), SessionCtx { my_index: i });
        self.pump_protocol(sid, vec![], Protocol::Aux)
    }

    // ---- 通用泵（keygen/aux/sign 三协议同构驱动） ----

    fn pump_protocol(
        &mut self,
        sid: &str,
        incoming: Vec<CgMessage>,
        proto: Protocol,
    ) -> DriverReply {
        let my = match self.ctx_index(sid) {
            Ok(i) => i,
            Err(e) => return Self::err(format!("pump {proto:?} {sid}: {e}")),
        };
        // 三协议同一 pump——闭包按协议取状态机槽位并 pump；完成时收产物。
        // with 返回 Result<Result<...>>（外层=registry 锁，内层=协议结果）→ flatten。
        let storage = self.storage.clone();
        let r = self
            .registry
            .with(
                sid,
                |s| -> eyre::Result<(Vec<CgMessage>, bool, Option<ProdKind>)> {
                    match proto {
                        Protocol::Keygen => {
                            let Some(sm) = s.keygen_state.as_mut() else {
                                return Err(eyre::eyre!("keygen not started for {sid}"));
                            };
                            let (out, fin, output) =
                                pump::<_, CgKeygenMsg>(sm.as_mut(), &incoming, my)?;
                            if let Some(res) = output {
                                let core = res.map_err(|e| eyre::eyre!("keygen failed: {e:?}"))?;
                                // 写钩子（PLAN-cggmp-keyshare-persistence §3.4）：
                                // core_share 先落盘成功才进 registry 并释放状态机。
                                // 落盘失败 = fail-closed：registry 不变、RPC 报错；
                                // Output 后状态机已 Exhausted 不可重 pump，调用方
                                // 收到错误即知本轮作废——重启后无残留，整组安全重跑。
                                if let Some(st) = &storage {
                                    let plaintext = crate::cggmp::encode_incomplete(&core)
                                        .map_err(|e| eyre::eyre!("keygen output encode: {e}"))?;
                                    crate::persistence::persist_cggmp_blob(
                                        sid,
                                        "incomplete",
                                        &st.base_dir,
                                        &plaintext,
                                        &st.key,
                                        st.key_version,
                                    )
                                    .map_err(|e| {
                                        eyre::eyre!(
                                            "keygen share persist failed (fail-closed): {e}"
                                        )
                                    })?;
                                }
                                s.core_share = Some(core);
                                s.keygen_state = None; // 完成：释放状态机
                            }
                            Ok((out, fin, None))
                        }
                        Protocol::Aux => {
                            let Some(sm) = s.aux_state.as_mut() else {
                                return Err(eyre::eyre!("aux not started for {sid}"));
                            };
                            let (out, fin, output) =
                                pump::<_, CgAuxMsg>(sm.as_mut(), &incoming, my)?;
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
                            let (out, fin, output) =
                                pump::<_, CgSignMsg>(sm.as_mut(), &incoming, my)?;
                            if let Some(res) = output {
                                let sig = res.map_err(|e| eyre::eyre!("sign failed: {e:?}"))?;
                                let (r_hex, s_hex) = signature_to_hex(&sig);
                                s.sign_state = None;
                                return Ok((out, fin, Some(ProdKind::Signature(r_hex, s_hex))));
                            }
                            Ok((out, fin, None))
                        }
                    }
                },
            )
            .and_then(|inner| inner);
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
        type Parts = (
            IncompleteKeyShare<Secp256k1>,
            cggmp21::key_share::AuxInfo<SecurityLevel128>,
        );
        type Dirty = cggmp21::key_share::DirtyKeyShare<Secp256k1, SecurityLevel128>;
        let storage = self.storage.clone();
        let r = self
            .registry
            .with(sid, |s| -> eyre::Result<()> {
                // 幂等：已完成（或从盘恢复）直接成功
                if s.key_share.is_some() {
                    return Ok(());
                }
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
                // 写钩子：keyshare（长期份额）先落盘成功才进 registry——
                // fail-closed 同 keygen（core/aux 仍在 registry，调用方可重试 assemble）
                if let Some(st) = &storage {
                    let plaintext = crate::cggmp::encode_key_share(&share)
                        .map_err(|e| eyre::eyre!("keyshare encode: {e}"))?;
                    crate::persistence::persist_cggmp_blob(
                        sid,
                        "keyshare",
                        &st.base_dir,
                        &plaintext,
                        &st.key,
                        st.key_version,
                    )
                    .map_err(|e| eyre::eyre!("keyshare persist failed (fail-closed): {e}"))?;
                }
                s.key_share = Some(share);
                Ok(())
            })
            .and_then(|inner| inner);
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
        // 持久化读守卫：registry 缺 key_share 时尝试盘上恢复——这是
        // "keygen 仪式一次、长期反复签名"的生产主路径（进程重启后
        // StartSign 直接从磁盘加载份额，无需重跑 DKG/aux）。
        // 解码失败 fail-closed（篡改/截断/错密钥 → 显式报错拒绝签名）。
        let have_key_share = match self.registry.with(sid, |s| s.key_share.is_some()) {
            Ok(v) => v,
            Err(e) => return Self::err(format!("start_sign {sid}: {e}")),
        };
        if !have_key_share {
            if let Some(ctx) = self.storage.clone() {
                match crate::persistence::load_cggmp_blob(sid, "keyshare", &ctx.base_dir, &ctx.key)
                {
                    Err(e) => return Self::err(format!("start_sign {sid}: restore: {e}")),
                    Ok(Some((_, plaintext))) => match crate::cggmp::decode_key_share(&plaintext) {
                        Ok(share) => {
                            if let Err(e) = self.registry.with(sid, |s| {
                                s.key_share = Some(share);
                            }) {
                                return Self::err(format!("start_sign {sid}: {e}"));
                            }
                        }
                        Err(e) => {
                            return Self::err(format!(
                                "start_sign {sid}: persisted keyshare invalid: {e}"
                            ))
                        }
                    },
                    Ok(None) => {}
                }
            }
        }
        // sign 会话上下文：my_index = 本方 keygen 索引（signers[i]——pump 编码
        // outgoing 的 sender；J 批 e2e 的 pullRelay(my_index=keygenIdx) 同语义）。
        // 恢复路径（重启后直接 StartSign）没有先跑 StartKeygen/StartAux，
        // ctx 在此补建——否则 pump 报 "no session context"。
        let my_keygen_index = signers_at_keygen.get(i as usize).copied().ok_or_else(|| {
            eyre::eyre!("start_sign {sid}: my_index_in_signers {i} out of signers range")
        });
        let my_keygen_index = match my_keygen_index {
            Ok(v) => v,
            Err(e) => return Self::err(format!("start_sign: {e}")),
        };
        self.ctxs.insert(
            sid.to_string(),
            SessionCtx {
                my_index: my_keygen_index,
            },
        );
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
                let share_static: &'static KeyShare<Secp256k1> = Box::leak(Box::new(share.clone()));
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
            .and_then(|inner| inner);
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
            .and_then(|inner| inner);
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
// 驱动线程 + 对外句柄（Send + Clone）+ 协调器 relay 池
// =========================================================================

/// CGGMP21 协调器 relay 池（发布/拉取消息——协调器是字节管道）。
///
/// 与 distributed.rs 的 DistRegistry.relays 同构但 0-based：
///   * `pool`：session_id → 待转发消息（`CgMessage` 0-based 三元组）
///   * `consumed`：`session:my_index` → 已消费**队列索引**集合（幂等拉取）
///
/// 池本身是共享数据（非 !Send 状态机）——放 handle 的 Arc<Mutex>，
/// 拉取方直接读写，不绕驱动线程（E/F 批架构：relay 是协调器职责）。
///
/// **消费幂等以队列索引为准（F 批 e2e 实证修正）**：初版按消息指纹
/// （sender:receiver:payload_len）去重——keygen 同轮多条同尺寸消息
/// （承诺/公钥材料等长是常态）互相吞掉，状态机永远等缺失消息
/// （200 轮空转）。队列在阶段内 append-only，索引天然唯一；
/// 阶段切换 `clear_session` 重置队列与消费记录。
#[derive(Default)]
pub struct CgRelayPool {
    pool: Mutex<HashMap<String, Vec<CgMessage>>>,
    consumed: Mutex<HashMap<String, std::collections::HashSet<usize>>>,
}

impl CgRelayPool {
    /// 发布消息（协调器侧追加；返回发布前的队列长度）。
    pub fn publish(&self, session_id: &str, msgs: Vec<CgMessage>) -> usize {
        let mut g = match self.pool.lock() {
            Ok(g) => g,
            Err(_) => return 0,
        };
        let queue = g.entry(session_id.to_string()).or_default();
        let before = queue.len();
        queue.extend(msgs);
        before
    }

    /// 清空会话消息队列与消费记录（协议阶段切换边界用）。
    ///
    /// 同一 session 的 keygen/aux/sign 三阶段共用一个队列——前一阶段的
    /// 尾巴消息会在后一阶段被错误拉取（round mismatch）。在 StartAux/
    /// StartSign（server 层）调用本方法清池，阶段边界即协议边界
    /// （队列索引随之重置，消费幂等重新计数）。
    pub fn clear_session(&self, session_id: &str) {
        if let Ok(mut g) = self.pool.lock() {
            g.remove(session_id);
        }
        // consumed 按 `session:my_index` 前缀清理
        if let Ok(mut g) = self.consumed.lock() {
            let prefix = format!("{session_id}:");
            g.retain(|k, _| !k.starts_with(&prefix));
        }
    }

    /// 拉取并消费本方尚未见过的消息（幂等——重复拉取不重复消费）。
    ///
    /// **按接收方过滤（F 批修正——GG20 relay_pull 同款缺陷的修复）**：
    /// 广播（receiver=None）对所有非 sender 方可拉；p2p（receiver=Some(idx)）
    /// **仅目标方 idx 可拉**——否则非目标方会把定向消息喂给状态机造成
    /// round mismatch（distributed.rs 的 relay_pull 只排除自发不按 receiver
    /// 过滤，该缺陷因无调用方从未暴露；本池随 F 批 e2e 修复）。
    pub fn pull(&self, session_id: &str, my_index: u16) -> Vec<CgMessage> {
        let relays = match self.pool.lock() {
            Ok(g) => g,
            Err(_) => return vec![],
        };
        let mut consumed = match self.consumed.lock() {
            Ok(g) => g,
            Err(_) => return vec![],
        };
        let key = format!("{session_id}:{my_index}");
        let seen = consumed.entry(key).or_default();
        let mut out = vec![];
        if let Some(queue) = relays.get(session_id) {
            for (idx, m) in queue.iter().enumerate() {
                // 接收方过滤：p2p 消息仅目标方可拉
                let for_me = match m.receiver {
                    None => true,                       // 广播：所有方
                    Some(target) => target == my_index, // p2p：仅目标方
                };
                if !for_me || m.sender == my_index {
                    continue;
                }
                // 幂等键 = 队列索引（append-only 队列内天然唯一）
                if seen.insert(idx) {
                    out.push(m.clone());
                }
            }
        }
        out
    }
}

/// 驱动线程句柄（RPC 层持有；线程内独占全部 !Send 状态机）。
///
/// `call` 阻塞直至驱动线程回执——RPC async 上下文应包
/// `tokio::task::block_in_place` 或经独立阻塞池；E 批测试直接同步调用。
#[derive(Clone)]
pub struct CgDriverHandle {
    tx: Sender<DriverEnvelope>,
    /// 协调器 relay 池（共享数据；发布方任意，拉取方按 my_index 幂等消费）。
    pub relay: Arc<CgRelayPool>,
}

impl CgDriverHandle {
    /// 启动驱动线程（通常进程单例，见 `global`）。
    ///
    /// 持久化上下文从环境变量解析（`StorageCtx::from_env`）——未配置则
    /// 持久化关闭（WARN 一次，协议照常）。
    pub fn start() -> Self {
        Self::start_with_storage(StorageCtx::from_env())
    }

    /// 启动驱动线程并显式指定持久化上下文（测试注入用——绕开环境变量，
    /// 避免并行测试的 env 竞态；`None` = 显式关闭持久化）。
    pub fn start_with_storage(storage: Option<StorageCtx>) -> Self {
        let (tx, rx) = channel::<DriverEnvelope>();
        std::thread::Builder::new()
            .name("cggmp-driver".to_string())
            .spawn(move || {
                let mut inner = DriverInner::with_storage(storage);
                while let Ok(env) = rx.recv() {
                    let reply = inner.handle(env.cmd);
                    let _ = env.reply_tx.send(reply);
                }
                tracing::info!("cggmp-driver thread exiting (all handles dropped)");
            })
            .expect("spawn cggmp-driver thread");
        Self {
            tx,
            relay: Arc::new(CgRelayPool::default()),
        }
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
            DriverReply::PumpResult {
                outgoing,
                finished,
                aggregate_public_key,
            } => {
                // keygen 第 0 轮是广播（Broadcast 消息 receiver=None）
                assert!(!outgoing.is_empty(), "round-0 must produce messages");
                assert!(
                    outgoing.iter().all(|m| m.receiver.is_none()),
                    "round-0 must be broadcast"
                );
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
            DriverReply::Status {
                has_keygen_state,
                has_key_share,
                ..
            } => {
                assert!(!has_keygen_state && !has_key_share);
            }
            other => panic!("expected Status, got {other:?}"),
        }
    }
}

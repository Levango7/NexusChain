//! v2.2.0 阶段二：CGGMP21 分散式门限签名（keygen + aux_info + sign 全链路）。
//!
//! ## 为什么迁移（阶段一的阻塞已解除）
//!
//! 阶段一用 multi-party-ecdsa 0.8.1（GG20）只做到 DKG 分散式——sign 阶段的
//! `OfflineProtocolMessage` 是 crate 私有，消息无法转发。CGGMP21 0.6.3
//! （LFDT-Lockness，Kudelski 审计）把全部协议消息类型暴露为 pub
//! （`keygen::ThresholdMsg` / `signing::msg::Msg` / `key_refresh::AuxOnlyMsg`），
//! **sign 分散式因此可行**。协议栈语义更正：原生 t-of-n 门限（签名恰好 t 方，
//! 不再有 GG20 的 t+1 怪癖）。
//!
//! ## 架构（与阶段一一致）
//!
//! - 各方本地跑同步状态机（`state-machine` feature 的 `wrap_protocol` 包装），
//!   协调器是纯字节管道（`CgMessage.payload_json` = 消息的 serde JSON；
//!   不解密、不落盘、不修改内容）
//! - 份额落盘走 persistence.rs 的 AES-256-GCM 层（加密序列化后的字节）
//!
//! ## ExecutionId 构造规范（防重放安全不变量）
//!
//! `ExecutionId` 要求"永不重复、全体一致"，否则协议 abort（上游原话）。
//! 本模块的构造规范：`protocol_tag || 0x00 || session_id || 0x00 || counter_be`——
//!     - protocol_tag：区分 keygen/aux/sign（防同 session 跨协议消息重放）
//!     - session_id：业务会话（DKG 会话 / 签名会话各自独立）
//!     - counter：同 session 同协议的重试序号（调用方保证单调递增）
//! 逐字节一致的 eid 才是同一个协议执行——协调器分发 eid 时全体一致即可信
//! （协调器是纯字节管道，不参与 eid 构造，只在分发时保证各节点收到
//! 完全相同的字节序列）。
//!
//! ## 依赖隔离说明
//!
//! cggmp21 系（round-based 0.4 / generic-ec 0.4 / sha2 0.10 / rug-GMP）
//! 与 GG20 系（round-based 0.1 / curv / sha2 0.9）按 major 版本在依赖树中
//! 共存；本模块只触 cggmp21 系类型（sha2_010 别名隔离），不与旧路径交叉。

use std::collections::HashMap;
use std::sync::Mutex;

use cggmp21::key_share::AuxInfo;
use cggmp21::round_based::state_machine::{ProceedResult, StateMachine as CgStateMachine};
use cggmp21::round_based::{Incoming, MessageDestination, MessageType, Outgoing};
use cggmp21::security_level::SecurityLevel128;
use cggmp21::supported_curves::Secp256k1;
use cggmp21::{signing::DataToSign, signing::Signature, IncompleteKeyShare, KeyShare};
use sha2_010::Sha256 as CgSha;

// 协议 tag（eid 构造规范的第一段）。
pub const PROTO_KEYGEN: &[u8] = b"nexus-cg-keygen";
pub const PROTO_AUX: &[u8] = b"nexus-cg-aux";
pub const PROTO_SIGN: &[u8] = b"nexus-cg-sign";

/// 按 eid 构造规范生成执行 ID。
///
/// 'static 生命周期由 Box::leak 固定（每 session 至多泄漏 ~64B，远低于
/// 内存压力）。若需严格回收可改为 registry 化的 eid 池——本批暂不引入。
pub fn execution_id(tag: &[u8], session_id: &str, counter: u32) -> cggmp21::ExecutionId<'static> {
    let mut buf = Vec::with_capacity(tag.len() + session_id.len() + 8);
    buf.extend_from_slice(tag);
    buf.push(0);
    buf.extend_from_slice(session_id.as_bytes());
    buf.push(0);
    buf.extend_from_slice(&counter.to_be_bytes());
    cggmp21::ExecutionId::new(Box::leak(buf.into_boxed_slice()))
}

// =========================================================================
// 份额序列化（落盘/加载）——crt/multiexp 显式清空
// =========================================================================

/// 落盘前清洗：`PartyAux.crt`（Paillier 私钥级敏感——上游原话 "extremely
/// sensitive! Leaking `crt` exposes Paillier private key"）与 `multiexp`
/// （体积大且可重算）**不落盘**。两字段均 `#[serde(default)]`，重载后
/// 按需 `precompute_crt`/`precompute_multiexp_tables` 重建。
pub fn sanitize_for_disk(
    share: &mut cggmp21::key_share::DirtyKeyShare<Secp256k1, SecurityLevel128>,
) {
    for party in share.aux.parties.iter_mut() {
        party.crt = None;
        party.multiexp = None;
    }
}

/// 序列化份额（含敏感字段清洗）→ JSON 字节（调用方负责 AES 加密后落盘）。
pub fn encode_key_share(share: &KeyShare<Secp256k1>) -> eyre::Result<Vec<u8>> {
    let mut dirty = share.clone().into_inner();
    sanitize_for_disk(&mut dirty);
    serde_json::to_vec(&dirty).map_err(|e| eyre::eyre!("encode key share: {e}"))
}

/// 反序列化份额（AES 解密后的明文 JSON）→ 校验 → `KeyShare`。
///
/// 反序列化份额（AES 解密后的明文 JSON）→ 校验 → `KeyShare`。
///
/// cggmp21 0.6.3 在 `DirtyKeyShare` 上实现 `Validate` trait——`Validate::validate`
/// 的默认实现调 `Valid::validate(self)` 返 `Valid<Self>`。cggmp21 的
/// `KeyShare = Valid<DirtyKeyShare>` 是类型别名，所以直接返 `KeyShare`。
pub fn decode_key_share(bytes: &[u8]) -> eyre::Result<KeyShare<Secp256k1>> {
    let dirty: cggmp21::key_share::DirtyKeyShare<Secp256k1, SecurityLevel128> =
        serde_json::from_slice(bytes).map_err(|e| eyre::eyre!("decode key share: {e}"))?;
    <cggmp21::key_share::DirtyKeyShare<Secp256k1, SecurityLevel128> as cggmp21::key_share::Validate>::validate(dirty)
        .map_err(|e| eyre::eyre!("key share invalid: {e:?}"))
}

/// 序列化 keygen 中间产物（IncompleteKeyShare，含本方秘密份额）。
pub fn encode_incomplete(core: &IncompleteKeyShare<Secp256k1>) -> eyre::Result<Vec<u8>> {
    serde_json::to_vec(core).map_err(|e| eyre::eyre!("encode incomplete share: {e}"))
}

pub fn decode_incomplete(bytes: &[u8]) -> eyre::Result<IncompleteKeyShare<Secp256k1>> {
    serde_json::from_slice(bytes).map_err(|e| eyre::eyre!("decode incomplete share: {e}"))
}

// =========================================================================
// 通用驱动：任意 CGGMP21 同步状态机的消息泵
// =========================================================================

/// 一条跨进程协议消息（与 distributed.rs 的 DistMessage 同构——
/// sender/receiver/payload_json 三元组，payload 是 `Msg` 的 serde JSON）。
///
/// round_based 0.4 跨进程消息包装是 `Incoming<M>` / `Outgoing<M>`，含
/// sender/receiver 与消息体 `M`。本结构序列化 `Incoming<M>` 即可走
/// 任意网络——JSON 形态由 `M` 自身的 serde 决定。
#[derive(Clone, Debug)]
pub struct CgMessage {
    pub sender: u16,
    pub receiver: Option<u16>,
    pub payload_json: String,
}

/// 喂入收到的消息、取出发出的消息；返回 (outgoing, finished)。
///
/// round-based 0.4 的 StateMachine API 是轮询式（`proceed()` 返回
/// ProceedResult 枚举），不是 0.1 的事件回调式（feed_messages/pull_outgoing）——
/// 本函数实现前者：循环 proceed 直到需要更多消息或完成。
///
/// 协议消息的 id（`MsgId = u64`）由调用方分配（CgMessage 暂不携带，
/// 默认给 0；协议对 id 不敏感，只对 sender/payload 敏感）。
pub fn pump<SM, M>(sm: &mut SM, incoming: &[CgMessage]) -> eyre::Result<(Vec<CgMessage>, bool)>
where
    SM: CgStateMachine<Msg = M>,
    M: serde::de::DeserializeOwned + serde::Serialize,
{
    // 1. 喂入收到的消息
    for m in incoming {
        let inner: M = serde_json::from_str(&m.payload_json)
            .map_err(|e| eyre::eyre!("bad protocol message json: {e}"))?;
        // receiver 字段：None = broadcast；Some(idx) = p2p
        let msg_type = if m.receiver.is_none() {
            MessageType::Broadcast
        } else {
            MessageType::P2P
        };
        let incoming_msg = Incoming {
            id: 0,
            sender: m.sender, // PartyIndex = u16 alias，直接传 u16
            msg_type,
            msg: inner,
        };
        sm.received_msg(incoming_msg).map_err(|_| {
            eyre::eyre!("state machine did not request this message (round mismatch)")
        })?;
    }

    // 2. 轮询驱动 proceed 取出消息
    let mut outgoing = Vec::new();
    let mut finished = false;
    loop {
        match sm.proceed() {
            ProceedResult::SendMsg(out) => {
                let Outgoing { msg, recipient } = out;
                let payload_json = serde_json::to_string(&msg)
                    .map_err(|e| eyre::eyre!("serialize outgoing message: {e}"))?;
                let (sender, receiver) = match recipient {
                    MessageDestination::AllParties => (0, None),
                    MessageDestination::OneParty(idx) => (idx, Some(idx)),
                };
                outgoing.push(CgMessage {
                    sender,
                    receiver,
                    payload_json,
                });
            }
            ProceedResult::NeedsOneMoreMessage => break,
            ProceedResult::Output(_) => {
                finished = true;
                break;
            }
            // Yielded/Error 是 round_based 0.4 异步/错误路径——同步状态机
            // 不会到达。到达则视为协议错误（fail-closed）。
            ProceedResult::Yielded => {
                return Err(eyre::eyre!(
                    "cggmp state machine unexpectedly yielded in sync mode"
                ));
            }
            ProceedResult::Error(e) => {
                return Err(eyre::eyre!("cggmp state machine returned error: {e:?}"));
            }
        }
    }

    Ok((outgoing, finished))
}

// =========================================================================
// 三协议的状态机工厂（各方本地构造）
// =========================================================================

/// 构造 threshold keygen 状态机（t-of-n，0-based 索引）。
///
/// 同步状态机不持有 Self trait（不稳定），改返**未指定 impl Trait**（仅
/// `+ 'static`），由 CgSession 字段通过具体路径处理（待 cggmp_state.rs
/// 拆分时填具体边界）。本批只保证编译通过 + 公开工厂函数。
pub fn keygen_state_machine(
    session_id: &str,
    counter: u32,
    i: u16,
    n: u16,
    t: u16,
) -> impl cggmp21::round_based::state_machine::StateMachine<
    Output = Result<IncompleteKeyShare<Secp256k1>, cggmp21::KeygenError>,
    Msg = cggmp21::keygen::ThresholdMsg<Secp256k1, SecurityLevel128, CgSha>,
> + 'static {
    let eid = execution_id(PROTO_KEYGEN, session_id, counter);
    cggmp21::keygen::<Secp256k1>(eid, i, n)
        .set_threshold(t)
        .into_state_machine(Box::leak(Box::new(cggmp21_keygen_rng())))
}

/// 构造 aux_info_gen 状态机（DKG 前置：Paillier 辅助数据）。
///
/// `PregeneratedPrimes` 是安全素数对（重操作）——生产建议预生成并 serde
/// 载入；此处每次现生成（测试规模可接受）。
pub fn aux_info_state_machine(
    session_id: &str,
    counter: u32,
    i: u16,
    n: u16,
) -> eyre::Result<
    impl cggmp21::round_based::state_machine::StateMachine<
            Output = Result<AuxInfo<SecurityLevel128>, cggmp21::KeyRefreshError>,
            Msg = cggmp21::key_refresh::AuxOnlyMsg<CgSha, SecurityLevel128>,
        > + 'static,
> {
    let eid = execution_id(PROTO_AUX, session_id, counter);
    let pregenerated = cggmp21::PregeneratedPrimes::generate(&mut cggmp21_keygen_rng());
    let builder = cggmp21::aux_info_gen(eid, i, n, pregenerated);
    Ok(builder.into_state_machine(Box::leak(Box::new(cggmp21_keygen_rng()))))
}

/// 构造 sign 状态机（t-of-n 签名——阶段二核心解锁）。
///
/// - `signers_at_keygen`：本批签名方在 keygen 时的原始索引（CGGMP21 要求
///   签名时知道各方 keygen 索引以做 Lagrange 换算）
/// - `i`：本方在**本批签名方**中的序号（0-based，0..t）
/// - `message_hash`：32 字节消息哈希
///
/// 不加 `+ 'static` bound：cggmp21 0.6.3 的 sign 状态机在 `parties_indexes_at_keygen`
/// 上持借用，调用方必须保证该 slice 与 `KeyShare` 在状态机生命周期内有效
/// （`&'a` 形式）。生产中 slice 来自 `CgSession` 自有的 `Vec<u16>` 字段。
pub fn sign_state_machine<'a>(
    session_id: &str,
    counter: u32,
    i: u16,
    signers_at_keygen: &'a [u16],
    key_share: &'a KeyShare<Secp256k1>,
    message_hash: [u8; 32],
) -> eyre::Result<
    impl cggmp21::round_based::state_machine::StateMachine<
            Output = Result<Signature<Secp256k1>, cggmp21::SigningError>,
            Msg = cggmp21::signing::msg::Msg<Secp256k1, CgSha>,
        > + 'a,
> {
    let eid = execution_id(PROTO_SIGN, session_id, counter);
    let data = build_data_to_sign(message_hash)?;
    let builder = cggmp21::signing(eid, i, signers_at_keygen, key_share);
    Ok(builder.sign_sync(Box::leak(Box::new(cggmp21_keygen_rng())), data))
}

fn build_data_to_sign(message_hash: [u8; 32]) -> eyre::Result<DataToSign<Secp256k1>> {
    // DataToSign::from_digest 接受 D: Digest——喂一个 sha2_010 哈希实例
    // （已用 final 32 字节）。注意：CGGMP21 文档明示 from_digest 会再执行
    // `mod curve order`（内部 Scalar::from_be_bytes_mod_order），所以这里传
    // 的是"原始 32 字节"，会被再 mod q，与"已 mod q 的标量"在数值上等价。
    use sha2_010::Digest;
    let mut hasher = CgSha::new();
    hasher.update(message_hash);
    Ok(DataToSign::from_digest::<CgSha>(hasher))
}

/// 进程级 RNG。
fn cggmp21_keygen_rng() -> rand_core::OsRng {
    rand_core::OsRng
}

// =========================================================================
// 注册表（session → 状态机，进程内单例；server 层经此驱动）
// =========================================================================
//
// 状态机的归宿选择：Rust 不允许 `type X = impl Trait`（稳定版），
// `dyn StateMachine<...>` 也不支持关联类型（异步生成器的本质限制）。
// 折中方案：每协议各自一个**结构体**持有状态 + 提供同名 `pump_*` 方法；
// `CgSession` 的三个字段是 `Option<Box<具体状态类型>>`——这与协调器
// 按 protocol tag 分发的架构天然契合。
//
// 本节代码暂留占位（三协议状态结构体的 `pump_*` 实现在后续
// `cggmp_state.rs` 拆分时再写）。本批先保证 cggmp.rs 编译通过。

/// CGGMP21 会话注册表。
pub struct CgRegistry {
    sessions: Mutex<HashMap<String, CgSession>>,
}

/// CgSession 字段：每个协议一个独立状态结构体的 Option<Box>。
/// 当前占位字段（实际类型由后续 pump 分离时填入）。
pub struct CgSession {
    /// 各协议状态机（占位 Box<dyn 不可用——后续填具体类型）
    pub keygen_state: Option<Box<dyn std::any::Any + Send>>,
    pub aux_state: Option<Box<dyn std::any::Any + Send>>,
    pub sign_state: Option<Box<dyn std::any::Any + Send>>,
    /// keygen 产出（本方 IncompleteKeyShare）
    pub core_share: Option<IncompleteKeyShare<Secp256k1>>,
    /// aux 产出（本方 AuxInfo）
    pub aux_info: Option<AuxInfo<SecurityLevel128>>,
    /// 合成的完整份额（core + aux）
    pub key_share: Option<KeyShare<Secp256k1>>,
}

impl CgRegistry {
    pub fn new() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
        }
    }

    /// session 槽位（无则建空）。
    pub fn with<T>(
        &self,
        session_id: &str,
        f: impl FnOnce(&mut CgSession) -> T,
    ) -> eyre::Result<T> {
        let mut g = self.sessions.lock().map_err(|e| eyre::eyre!("lock: {e}"))?;
        Ok(f(g.entry(session_id.to_string()).or_default()))
    }
}

// Box<dyn Any> 不实现 Default（std 未提供 dyn trait 的默认构造），
// 手动 impl 是当前唯一写法。clippy::derivable_impls 误报——显式忽略。
#[allow(clippy::derivable_impls)]
impl Default for CgSession {
    fn default() -> Self {
        Self {
            keygen_state: None,
            aux_state: None,
            sign_state: None,
            core_share: None,
            aux_info: None,
            key_share: None,
        }
    }
}

impl Default for CgRegistry {
    fn default() -> Self {
        Self::new()
    }
}

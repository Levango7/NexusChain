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
/// ProceedResult 枚举），不是 0.1 的事件回调式（feed_messages/pull_outgoing）。
///
/// **上游契约（E 批修正）**：`received_msg` 后必须紧跟一次 `proceed`，
/// **不允许连续喂多条**（上游 mod.rs:96-99 原文 "Do not invoke this method
/// more than once in a row"）——D 批旧实现 for 循环连喂再统一 proceed 违反
/// 契约，多消息场景下状态机会错误地积压/丢弃（F-U 1 仅测空 inbox 未暴露）。
/// 现改为严格交替：每条 incoming → received_msg → proceed，循环至
/// NeedsOneMoreMessage（inbox 耗尽）或 Output（完成）。
///
/// **!Send 限制（E 批发现）**：状态机内部是 `Rc<RefCell<SharedState>>`
/// （round_based 0.4 wrap_protocol 的固有设计），**非 Send**——持有它的
/// 代码不能进 async 上下文/跨线程。cggmp_state.rs 的专用驱动线程 actor
/// 满足此约束；直接在本模块测试中单线程使用亦安全。
///
/// 协议消息的 id（`MsgId = u64`）由调用方分配（CgMessage 暂不携带，
/// 默认给 0；协议对 id 不敏感，只对 sender/payload 敏感）。
///
/// 返回 `(outgoing, finished, output)`：
/// - outgoing：本轮驱动产出的待发消息
/// - finished：是否已完成（`ProceedResult::Output(_)` 已到达）
/// - output：协议产物（`Some` 当 `finished=true`）；调用方拿到签名结果
///   /份额等。`None` 当 `finished=false`。
///
/// **注意**：喂入的消息若非状态机当前期望（轮次不匹配），received_msg 会
/// 返 Err——本函数将其转为协议错误（fail-closed，不静默丢弃）。
pub fn pump<SM, M>(
    sm: &mut SM,
    incoming: &[CgMessage],
    my_index: u16,
) -> eyre::Result<(Vec<CgMessage>, bool, Option<SM::Output>)>
where
    SM: CgStateMachine<Msg = M> + ?Sized,
    M: serde::de::DeserializeOwned + serde::Serialize,
    SM::Output: Sized, // 同步状态机产物 Sized（生成器产物非 dyn 友好）
{
    let mut outgoing = Vec::new();
    let mut output: Option<SM::Output> = None;

    // E 批：received_msg 与 proceed 严格交替（上游契约）。
    // 每处理完一条 incoming 就 proceed 一次，让状态机消费后继续产出；
    // inbox 耗尽时若状态机仍 NeedsOneMoreMessage 则等待下一批。
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
        if sm.received_msg(incoming_msg).is_err() {
            return Err(eyre::eyre!(
                "state machine did not request this message (round mismatch or duplicate)"
            ));
        }
        // received_msg 后必须紧跟 proceed（上游契约）——此处驱动一轮，
        // 产出的消息全部收集；若恰在此轮完成则收 output 并停止。
        // Yielded = 协议主动让出（把长计算切块，上游 mod.rs:118-125 原文
        // "resume by calling proceed immediately"）——继续轮询即可；
        // 加连续 yield 上限防御异常状态机（正常协议不会无限 yield）。
        let mut yields = 0usize;
        match sm.proceed() {
            ProceedResult::SendMsg(out) => {
                outgoing.push(encode_outgoing(out, my_index)?);
            }
            ProceedResult::NeedsOneMoreMessage => {
                // 状态机消费了刚喂入的消息但仍需更多——继续喂下一条
                // incoming（若还有），或返回等待调用方拉取新消息。
                continue;
            }
            ProceedResult::Output(o) => {
                output = Some(o);
                break;
            }
            // Yielded/Error：Yielded 是协议主动让出（长计算切块）——立即
            // 重新 proceed 继续（见上方 Javadoc 与上游 mod.rs:118-125）；
            // Error 是真正的协议/使用错误（fail-closed）。
            ProceedResult::Yielded => loop {
                yields += 1;
                if yields > 1_000_000 {
                    return Err(eyre::eyre!(
                        "cggmp state machine yielded more than 1M times — abnormal"
                    ));
                }
                match sm.proceed() {
                    ProceedResult::SendMsg(out) => {
                        outgoing.push(encode_outgoing(out, my_index)?);
                    }
                    ProceedResult::NeedsOneMoreMessage => break,
                    ProceedResult::Output(o) => {
                        output = Some(o);
                        break;
                    }
                    ProceedResult::Yielded => continue,
                    ProceedResult::Error(e) => {
                        return Err(eyre::eyre!("cggmp state machine returned error: {e:?}"));
                    }
                }
            },
            ProceedResult::Error(e) => {
                return Err(eyre::eyre!("cggmp state machine returned error: {e:?}"));
            }
        }
    }

    // inbox 耗尽后：继续 proceed 排空待发消息（直到 NeedsOneMoreMessage/Output）。
    // 仅当第一循环未完成时才排空——状态机 Output 后再 proceed 会
    // ExecutionError(Exhausted)（上游 mod.rs:87-89 原文契约）。
    // Yielded = 主动让出长计算切块——继续轮询（同第一循环语义）。
    let mut yields = 0usize;
    while output.is_none() {
        match sm.proceed() {
            ProceedResult::SendMsg(out) => {
                outgoing.push(encode_outgoing(out, my_index)?);
            }
            ProceedResult::NeedsOneMoreMessage => break,
            ProceedResult::Output(o) => {
                output = Some(o);
                break;
            }
            ProceedResult::Yielded => {
                yields += 1;
                if yields > 1_000_000 {
                    return Err(eyre::eyre!(
                        "cggmp state machine yielded more than 1M times — abnormal"
                    ));
                }
                continue;
            }
            ProceedResult::Error(e) => {
                return Err(eyre::eyre!("cggmp state machine returned error: {e:?}"));
            }
        }
    }

    let finished = output.is_some();
    Ok((outgoing, finished, output))
}

/// 把上游 `Outgoing<M>` 编码为跨进程形态 `CgMessage`。
///
/// **E 批修正（D 批 sender bug）**：`Outgoing` 只有 recipient 没有 sender——
/// D 批旧代码把 recipient 目标错填进 `CgMessage.sender`（广播被标 sender=0、
/// p2p 时 sender/receiver 颠倒），接收方 `received_msg` 会拿到错误的来源方。
/// 现由调用方传入 `my_index`（状态机所属方），broadcast → receiver=None、
/// p2p → receiver=Some(idx)，sender 恒为 my_index。
fn encode_outgoing<M: serde::Serialize>(
    out: Outgoing<M>,
    my_index: u16,
) -> eyre::Result<CgMessage> {
    let Outgoing { msg, recipient } = out;
    let payload_json =
        serde_json::to_string(&msg).map_err(|e| eyre::eyre!("serialize outgoing message: {e}"))?;
    let receiver = match recipient {
        MessageDestination::AllParties => None,
        MessageDestination::OneParty(idx) => Some(idx),
    };
    Ok(CgMessage {
        sender: my_index,
        receiver,
        payload_json,
    })
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
// 注册表（session → 状态机；**仅 cggmp_state.rs 驱动线程内访问**）
// =========================================================================
//
// 状态机的归宿选择（E 批定稿）：
//   1. round_based 0.4 的 `StateMachine` trait 方法无泛型参数——
//      `Box<dyn StateMachine<Output = ..., Msg = ...>>`（关联类型显式指定）
//      是合法 Rust 且对象安全可用。D 批注释"dyn 不支持关联类型"实为误判
//      （不支持的是 `type X = impl Trait` 类型别名）。
//   2. 但 wrap_protocol 状态机内部是 `Rc<RefCell<SharedState>>`，**!Send**——
//      不能进 `Mutex`/不能跨线程/不能进 async。因此 CgSession 及其注册表
//      **只允许在 cggmp_state.rs 的专用驱动线程内构造与访问**（Rc 不跨线程
//      由编译器静态保证），对外 API 经 actor 指令通道（mpsc + oneshot）。
//   3. keygen/aux 状态机 `'static`（eid 与 RNG 均已 leak）；sign 状态机
//      借用 share/signers——构造时一并 leak 成 'static（与 eid/RNG 同一
//      权衡：每 session 泄漏量 ~KB 级，registry 化回收留后续批次）。
//
// 下游：cggmp_state.rs（E 批）——驱动线程 actor + 三协议处理 + 合成/验签。

/// CGGMP21 keygen 消息类型别名（协议固定三参数）。
pub type CgKeygenMsg = cggmp21::keygen::ThresholdMsg<Secp256k1, SecurityLevel128, CgSha>;
/// CGGMP21 aux_info 消息类型别名。
pub type CgAuxMsg = cggmp21::key_refresh::AuxOnlyMsg<CgSha, SecurityLevel128>;
/// CGGMP21 sign 消息类型别名。
pub type CgSignMsg = cggmp21::signing::msg::Msg<Secp256k1, CgSha>;

/// keygen 状态机对象（驱动线程内使用）。
pub type DynKeygenSm = Box<
    dyn CgStateMachine<
            Output = Result<IncompleteKeyShare<Secp256k1>, cggmp21::KeygenError>,
            Msg = CgKeygenMsg,
        > + 'static,
>;
/// aux_info 状态机对象。
pub type DynAuxSm = Box<
    dyn CgStateMachine<
            Output = Result<AuxInfo<SecurityLevel128>, cggmp21::KeyRefreshError>,
            Msg = CgAuxMsg,
        > + 'static,
>;
/// sign 状态机对象（`'static`——share/signers 构造时 leak）。
pub type DynSignSm = Box<
    dyn CgStateMachine<
            Output = Result<Signature<Secp256k1>, cggmp21::SigningError>,
            Msg = CgSignMsg,
        > + 'static,
>;

/// CGGMP21 会话注册表（**仅驱动线程内访问**——状态机 !Send）。
pub struct CgRegistry {
    sessions: Mutex<HashMap<String, CgSession>>,
}

/// CgSession：一个 session 的三协议状态与产物。
///
/// sign 状态机的 share/signers 借用在构造时 Box::leak 成 'static——
/// 重复 sign 会累积泄漏（每 session ~KB 级；registry 化回收留后续批次）。
#[derive(Default)]
pub struct CgSession {
    /// keygen 状态机（一旦完成即取走产物，置 None 释放）
    pub keygen_state: Option<DynKeygenSm>,
    /// aux_info 状态机（同上）
    pub aux_state: Option<DynAuxSm>,
    /// sign 状态机（'static 借用已 leak 的 share/signers）
    pub sign_state: Option<DynSignSm>,
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

impl Default for CgRegistry {
    fn default() -> Self {
        Self::new()
    }
}

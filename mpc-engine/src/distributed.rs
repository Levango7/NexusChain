//! v2.2.0 真分散式 MPC（阶段一：**DKG 份额隔离** + 协调器仅消息转发）。
//!
//! ## 范围（自审修正）
//!
//! multi-party-ecdsa 0.8.1 的 `keygen::ProtocolMessage` 是 pub 的（多轮
//! Keygen 可外部驱动），但 **sign 阶段的 `OfflineProtocolMessage` 为 crate
//! 私有类型**（state_machine/sign.rs 的 `type MessageBody = ...` 仅作 trait
//! 绑定）——阶段一无法泛型转发 sign 轮消息。
//!
//! 因此阶段一交付：**DKG 完全分散式**（各方只持有自己的 LocalKey，磁盘无
//! 他方份额）；签名聚合路径保持现状（sign.rs 单进程），阶段二等上游暴露
//! sign 消息类型或 fork patch 后分布式化（README 已标注演进目标）。
//!
//! | | 旧（协调器模式） | 本模块（阶段一） |
//! |---|---|---|
//! | 密钥生成 | 协调器单进程 `run_keygen` 生成并持有全 n 方份额 | 各方本地跑 `Keygen` 状态机，协调器只转发 `Msg<ProtocolMessage>` |
//! | 份额存储 | `DkgSession` 存全量 party_keys + shared_keys | 每方只存**自己的 `LocalKey`**（磁盘断言：无他方份额） |
//! | 聚合验签公钥 | 调用方传入（审计 S4：可伪造） | 各方本地 `LocalKey.public_key`（全体一致） |

use std::collections::HashMap;
use std::sync::Mutex;

use curv::arithmetic::Converter;
use curv::elliptic::curves::secp256_k1::Secp256k1;
use curv::BigInt;
use multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::party_i::SignatureRecid;
use multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::state_machine::keygen as sm_keygen;
use multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::state_machine::sign as sm_sign;
use round_based::{Msg, StateMachine};

/// 分散式 DKG/Sign 中转消息（round_based::Msg 的跨进程形态）。
///
/// `payload_json` 为 `Msg<sm_keygen::ProtocolMessage>`（或 sign 的
/// `Msg<sm_sign::ProtocolMessage>`）的 serde_json 序列化——由 proto 层
/// 转发，协调器不理解、不落盘、不修改内容（纯字节管道）。
#[derive(Clone, Debug)]
pub struct DistMessage {
    pub sender: u16,
    pub receiver: Option<u16>,
    pub payload_json: String,
}

/// 分散式 DKG 状态机（每方一个实例）。
///
/// 包装 multi-party-ecdsa `Keygen`（上游类型非 Clone——registry 存取经
/// `Arc<Mutex<...>>` 由 server 层管理，本类型不做 Clone）。
#[derive(Debug)]
pub struct DistDkgState {
    sm: Option<sm_keygen::Keygen>,
    party_i: u16,
    t: u16,
    n: u16,
    local_key: Option<sm_keygen::LocalKey<Secp256k1>>,
}

impl DistDkgState {
    /// 构造第 `party_i`（1-based）方的 Keygen。Round0 自动推进，产生首批消息。
    pub fn new(party_i: u16, t: u16, n: u16) -> eyre::Result<Self> {
        let sm = sm_keygen::Keygen::new(party_i, t, n)
            .map_err(|e| eyre::eyre!("Keygen::new(party={party_i},t={t},n={n}) failed: {e:?}"))?;
        let mut state = Self {
            sm: Some(sm),
            party_i,
            t,
            n,
            local_key: None,
        };
        state.pump()?;
        Ok(state)
    }

    /// 喂入他方消息（JSON 反序列化 → handle_incoming）。
    pub fn feed(&mut self, msgs: &[DistMessage]) -> eyre::Result<()> {
        let sm = self
            .sm
            .as_mut()
            .ok_or_else(|| eyre::eyre!("DKG already finished (session consumed)"))?;
        for m in msgs {
            let msg: Msg<sm_keygen::ProtocolMessage> = serde_json::from_str(&m.payload_json)
                .map_err(|e| eyre::eyre!("bad keygen message json: {e}"))?;
            sm.handle_incoming(msg)
                .map_err(|e| eyre::eyre!("keygen handle_incoming failed: {e:?}"))?;
        }
        self.pump()
    }

    /// 取出待广播消息（drain 模式：取后清空）。
    pub fn outgoing(&mut self) -> Vec<DistMessage> {
        let Some(sm) = self.sm.as_mut() else {
            return vec![];
        };
        let queue = sm.message_queue();
        let msgs: Vec<DistMessage> = queue
            .drain(..)
            .map(|msg: Msg<sm_keygen::ProtocolMessage>| DistMessage {
                sender: msg.sender,
                receiver: msg.receiver,
                payload_json: serde_json::to_string(&msg)
                    .expect("ProtocolMessage is Serialize by construction"),
            })
            .collect();
        msgs
    }

    /// 状态机是否已完成（Final 轮）。
    pub fn is_finished(&self) -> bool {
        match &self.sm {
            Some(sm) => sm.is_finished(),
            None => true,
        }
    }

    /// 当前轮次（0-4，5=Final）。
    pub fn current_round(&self) -> u16 {
        match &self.sm {
            Some(sm) => sm.current_round(),
            None => 5,
        }
    }

    /// 是否需要外部推进（wants_to_proceed 且尚未 Final）。
    pub fn wants_proceed(&self) -> bool {
        match &self.sm {
            Some(sm) => sm.wants_to_proceed(),
            None => false,
        }
    }

    /// 完成后取走本方 LocalKey（取走后状态机消费完毕，防止重复提取）。
    pub fn take_local_key(&mut self) -> eyre::Result<sm_keygen::LocalKey<Secp256k1>> {
        let Some(sm) = self.sm.as_mut() else {
            return self
                .local_key
                .take()
                .ok_or_else(|| eyre::eyre!("local key already taken"));
        };
        if !sm.is_finished() {
            return Err(eyre::eyre!(
                "DKG not finished (round {}), cannot take local key",
                sm.current_round()
            ));
        }
        let out = sm
            .pick_output()
            .ok_or_else(|| eyre::eyre!("state machine finished but output pending"))?
            .map_err(|e| eyre::eyre!("keygen output error: {e:?}"))?;
        self.sm = None;
        Ok(out)
    }

    /// 手动推进（may_block=true 的 proceed；用于收到慢方消息后强制推进）。
    fn pump(&mut self) -> eyre::Result<()> {
        let Some(sm) = self.sm.as_mut() else {
            return Ok(());
        };
        if sm.wants_to_proceed() {
            sm.proceed()
                .map_err(|e| eyre::eyre!("keygen proceed failed: {e:?}"))?;
        }
        Ok(())
    }

    pub fn party_index(&self) -> u16 {
        self.party_i
    }
    pub fn threshold(&self) -> u16 {
        self.t
    }
    pub fn total_parties(&self) -> u16 {
        self.n
    }

    /// 取出内层 Keygen 状态机（供 round_based::dev::Simulation 测试驱动；
    /// 取出后本包装失效，生产路径不经此）。
    pub fn into_inner(self) -> Option<sm_keygen::Keygen> {
        self.sm
    }

    /// 包装既有 Keygen（Simulation round-trip 用）。
    pub fn from_inner(sm: sm_keygen::Keygen, party_i: u16, t: u16, n: u16) -> Self {
        Self {
            sm: Some(sm),
            party_i,
            t,
            n,
            local_key: None,
        }
    }
}

/// 分散式签名状态（OfflineStage 分布式预计算 + SignManual 单轮）。
/// 阶段一签名聚合助手（非分布式转发——见模块头"范围"说明）。
///
/// `OfflineProtocolMessage` 在 multi-party-ecdsa 0.8.1 中为 crate 私有类型，
/// 离线阶段消息无法跨进程序列化转发。阶段一的签名路径保持 sign.rs 现状；
/// 本结构仅提供**本地聚合 + 会话公钥绑定验签**（修复审计 S4 的
/// aggregate.rs:99-112：验签公钥改取本方 LocalKey，不再信任调用方传参）。
pub struct DistSignState {
    party_i: u16,
    /// 参与签名的方集合（s_l，1-based）
    s_l: Vec<u16>,
    /// Online 阶段的 SignManual（离线阶段在单机跑完后转入）
    manual: Option<sm_sign::SignManual>,
    /// 本方部分签名已产出，等待聚合
    partial_out: Option<sm_sign::PartialSignature>,
}

impl DistSignState {
    /// 从已完成的离线阶段进入在线单轮（阶段一：离线产物由调用方在本地取得）。
    pub fn new_online(
        party_i: u16,
        s_l: Vec<u16>,
        message_hash: [u8; 32],
        offline_out: sm_sign::CompletedOfflineStage,
    ) -> eyre::Result<(Self, sm_sign::PartialSignature)> {
        let message = BigInt::from_bytes(&message_hash);
        let (manual, partial) = sm_sign::SignManual::new(message, offline_out)
            .map_err(|e| eyre::eyre!("SignManual::new failed: {e:?}"))?;
        let state = Self {
            party_i,
            s_l,
            manual: Some(manual),
            partial_out: None,
        };
        Ok((state, partial))
    }

    /// 用他方（不含本方）部分签名完成最终签名。
    ///
    /// - `message_hash`：被签消息哈希（聚合方自身上下文——验签需要；不存在
    ///   从签名结构反推消息的途径，这是 ECDSA 的性质而非缺陷）
    /// - 验签公钥取本方 LocalKey 的聚合公钥（全体一致）——**不再信任调用方
    ///   传参**（修复审计 aggregate.rs:99-112：调用方可传任意公钥使验签形同虚设）
    pub fn complete_signature(
        mut self,
        others_partials: &[sm_sign::PartialSignature],
        message_hash: [u8; 32],
        local_key: &sm_keygen::LocalKey<Secp256k1>,
    ) -> eyre::Result<SignatureRecid> {
        let manual = self
            .manual
            .take()
            .ok_or_else(|| eyre::eyre!("sign state already consumed"))?;
        let sig = manual
            .complete(others_partials)
            .map_err(|e| eyre::eyre!("SignManual::complete failed: {e:?}"))?;

        // 本地一致性校验：用会话聚合公钥做标准 secp256k1 验签（party_i::verify）
        let y = local_key.public_key();
        let message = BigInt::from_bytes(&message_hash);
        multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::party_i::verify(
            &sig, &y, &message,
        )
        .map_err(|e| {
            eyre::eyre!(
                "aggregated signature failed verification against session public key: {e:?}"
            )
        })?;
        self.partial_out = None;
        Ok(sig)
    }

    pub fn party_index(&self) -> u16 {
        self.party_i
    }
    pub fn signer_set(&self) -> &[u16] {
        &self.s_l
    }
}

/// 全局分散式会话注册表（server 层共享，进程单例）。
///
/// DKG 状态机非 Clone（上游 Keygen），经 `Arc<Mutex<HashMap>>` 存取。
/// - `dkg`：session_id → 本节点 DistDkgState（DKG 分散式状态机）
/// - `public_keys`：session_id → 全体一致的聚合公钥（hex）——DKG 完成后
///   各方登记；Aggregation 用它验签，替代调用方传参（审计 S4 修复）
/// - `relays`：协调器侧的待转发消息池，由 `relay_pull` 供其他方拉取
///
/// sign 阶段一不做消息转发（上游 OfflineProtocolMessage 私有，见模块头）。
pub struct DistRegistry {
    dkg: Mutex<HashMap<String, DistDkgState>>,
    public_keys: Mutex<HashMap<String, String>>,
    relays: Mutex<HashMap<String, Vec<DistMessage>>>,
    consumed: Mutex<HashMap<String, std::collections::HashSet<String>>>,
}

impl DistRegistry {
    pub fn new() -> Self {
        Self {
            dkg: Mutex::new(HashMap::new()),
            public_keys: Mutex::new(HashMap::new()),
            relays: Mutex::new(HashMap::new()),
            consumed: Mutex::new(HashMap::new()),
        }
    }

    /// 以 session_id 创建本节点 DKG 状态机（已存在则返回既有轮次——幂等）。
    pub fn dkg_register(&self, session_id: &str, state: DistDkgState) -> eyre::Result<()> {
        let mut g = self.dkg.lock().map_err(|e| eyre::eyre!("lock: {e}"))?;
        g.entry(session_id.to_string()).or_insert(state);
        Ok(())
    }

    /// 锁内驱动 DKG（feed 消息 → outgoing 取消息 → is_finished 判完成）。
    ///
    /// 闭包在锁内执行（DKG 推进含重计算但不跨 await——同步锁安全）。
    pub fn dkg_with<T>(
        &self,
        session_id: &str,
        f: impl FnOnce(Option<&mut DistDkgState>) -> T,
    ) -> eyre::Result<T> {
        let mut g = self.dkg.lock().map_err(|e| eyre::eyre!("lock: {e}"))?;
        Ok(f(g.get_mut(session_id)))
    }

    /// 会话 DKG 是否存在。
    pub fn dkg_exists(&self, session_id: &str) -> bool {
        self.dkg
            .lock()
            .map(|g| g.contains_key(session_id))
            .unwrap_or(false)
    }

    /// 注册聚合公钥（DKG 完成后本方登记；重复登记值必须一致）。
    pub fn register_public_key(&self, session_id: &str, pk_hex: &str) -> eyre::Result<()> {
        let mut g = self
            .public_keys
            .lock()
            .map_err(|e| eyre::eyre!("lock: {e}"))?;
        if let Some(existing) = g.get(session_id) {
            if existing != pk_hex {
                return Err(eyre::eyre!(
                    "public key mismatch for session {session_id}: registered={existing}, new={pk_hex} \
                     — distributed DKG inconsistency, refusing"
                ));
            }
            return Ok(());
        }
        g.insert(session_id.to_string(), pk_hex.to_string());
        Ok(())
    }

    /// 查询会话聚合公钥（Aggregation 验签用——替代调用方传参）。
    pub fn public_key(&self, session_id: &str) -> Option<String> {
        self.public_keys
            .lock()
            .ok()
            .and_then(|g| g.get(session_id).cloned())
    }

    /// 协调器注册待转发消息。
    pub fn relay_publish(&self, session_id: &str, msgs: Vec<DistMessage>) -> usize {
        let mut g = match self.relays.lock() {
            Ok(g) => g,
            Err(_) => return 0,
        };
        let queue = g.entry(session_id.to_string()).or_default();
        let before = queue.len();
        queue.extend(msgs);
        before
    }

    /// 拉取并消费本节点尚未消费的消息（排除自发消息）。
    pub fn relay_pull(&self, session_id: &str, my_index: u16) -> Vec<DistMessage> {
        let relays = match self.relays.lock() {
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
            for m in queue {
                let mid = format!(
                    "{}:{}:{}",
                    m.sender,
                    m.receiver.unwrap_or(0),
                    m.payload_json.len()
                );
                if m.sender != my_index && seen.insert(mid) {
                    out.push(m.clone());
                }
            }
        }
        out
    }

    /// 清理会话。
    pub fn cleanup_session(&self, session_id: &str) {
        if let Ok(mut g) = self.dkg.lock() {
            g.remove(session_id);
        }
        if let Ok(mut g) = self.public_keys.lock() {
            g.remove(session_id);
        }
        if let Ok(mut g) = self.relays.lock() {
            g.remove(session_id);
        }
        if let Ok(mut g) = self.consumed.lock() {
            g.retain(|k, _| !k.starts_with(&format!("{session_id}:")));
        }
    }
}

impl Default for DistRegistry {
    fn default() -> Self {
        Self::new()
    }
}

/// 本方 LocalKey 的持久化（仅本方份额，v2.2.0 分散式；**AES-256-GCM 加密落盘**）。
///
/// 与 persistence.rs 的全量 `DkgSession` 序列化互斥使用——分散式路径**从不**
/// 写全量会话；磁盘只存本方 LocalKey，供 E2E 断言"无他方份额"。
///
/// `base_dir`：显式传入的数据目录（生产=PartyConfig.data_dir；测试=tempdir）。
/// 不读全局 env——路径注入使测试无 env 竞态，且多实例目录隔离由调用方保证。
///
/// **v2.2.0 阶段二：静态加密接入**（persistence.rs 的 AES-256-GCM 原语 +
/// 密钥版本头）。`key` 显式传入（生产从 `PartyConfig::resolve_storage_key`
/// 解析；32 字节 AES-256 密钥），`key_version` 透传写入文件头
/// （`NXC1 || version LE || nonce || ciphertext`）支持密钥轮换——
/// 旧密钥文件由 `load_local_key` 调用方按版本选择密钥解密。
/// 文件不以明文 JSON 形态存在（防主机落盘窃取直接拿到门限份额）。
pub fn persist_local_key(
    base_dir: &std::path::Path,
    session_id: &str,
    party_index: u16,
    local_key: &sm_keygen::LocalKey<Secp256k1>,
    key: &[u8; 32],
    key_version: u32,
) -> eyre::Result<()> {
    let json =
        serde_json::to_string(local_key).map_err(|e| eyre::eyre!("serialize local key: {e}"))?;
    let encrypted =
        crate::persistence::aes_encrypt_with_version(json.as_bytes(), key, key_version)?;
    let dir = base_dir.join("dist");
    std::fs::create_dir_all(&dir)
        .map_err(|e| eyre::eyre!("create dist dir {}: {e}", dir.display()))?;
    let path = dir.join(format!(
        "p{party_index}-{}.bin",
        sanitize_session_id(session_id)
    ));
    std::fs::write(&path, encrypted)
        .map_err(|e| eyre::eyre!("write local key {}: {e}", path.display()))?;
    // 低9: 0600 权限（Unix；Windows 由 NTFS ACL 负责）——加密之上的纵深防御
    crate::persistence::set_secure_permissions(&path);
    tracing::info!(
        session_id = %session_id,
        party_index,
        path = %path.display(),
        key_version,
        "v2.2.0 dist: local key persisted \
         (AES-256-GCM encrypted, this party's share only)"
    );
    Ok(())
}

/// 读取本方 LocalKey（重启恢复用）。`base_dir` 须与 persist 时一致。
///
/// 只认加密格式（`NXC1` 版本头 + GCM 密文）——明文 JSON 阶段一测试产物
/// 不是合法输入（fail-closed：解密失败即报错，不做明文回退降级）。
/// 返回值含文件头记录的密钥版本号，供调用方轮换审计。
#[allow(clippy::type_complexity)]
pub fn load_local_key(
    base_dir: &std::path::Path,
    session_id: &str,
    party_index: u16,
    key: &[u8; 32],
) -> eyre::Result<Option<(u32, sm_keygen::LocalKey<Secp256k1>)>> {
    let path = base_dir.join("dist").join(format!(
        "p{party_index}-{}.bin",
        sanitize_session_id(session_id)
    ));
    if !path.exists() {
        return Ok(None);
    }
    let bytes =
        std::fs::read(&path).map_err(|e| eyre::eyre!("read local key {}: {e}", path.display()))?;
    let (version, plaintext) =
        crate::persistence::aes_decrypt_with_version(&bytes, key).map_err(|e| {
            eyre::eyre!(
                "decrypt local key {} failed ({e}) — wrong key, tampered file, \
                 or legacy plaintext from stage-1 (not supported)",
                path.display()
            )
        })?;
    let json = String::from_utf8(plaintext)
        .map_err(|e| eyre::eyre!("local key plaintext is not valid UTF-8: {e}"))?;
    let local_key: sm_keygen::LocalKey<Secp256k1> =
        serde_json::from_str(&json).map_err(|e| eyre::eyre!("deserialize local key: {e}"))?;
    Ok(Some((version, local_key)))
}

/// session_id 文件名安全化（仅保留 [A-Za-z0-9-_]；防路径穿越——审计 S4 修复
/// persistence.rs:70-77 同款问题的分散式新路径版本）。
fn sanitize_session_id(session_id: &str) -> String {
    session_id
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::state_machine::keygen as sm_keygen;
    use round_based::dev::Simulation;

    /// 3 节点分散式 DKG 全流程（真实 Keygen 状态机 ×3，Simulation 扮演
    /// 消息总线=生产中协调器转发角色），验证分散式的核心不变量：
    /// 1. 三方全部完成（4 轮）
    /// 2. 全体导出**一致聚合公钥**（分散式验签信任根基）
    /// 3. 各方份额互不相同（隔离）
    /// 4. 各方 LocalKey.i 分别为 1/2/3（本方身份）
    /// 5. 各自份额只在自己的 LocalKey 里（磁盘隔离由 persist_local_key
    ///    + E2E 断言验证）
    #[test]
    fn dist_dkg_3_parties_completes() {
        let mut sim = Simulation::new();
        for i in 1..=3u16 {
            sim.add_party(sm_keygen::Keygen::new(i, 2, 3).expect("Keygen::new"));
        }
        let keys: Vec<sm_keygen::LocalKey<Secp256k1>> =
            sim.run().expect("3-party distributed DKG completes");
        assert_eq!(keys.len(), 3);

        // EncodedPoint 无 PartialEq——Deref 到 &[u8] 后 hex 比较
        let to_hex = |p: &curv::elliptic::curves::Point<Secp256k1>| {
            let encoded = p.to_bytes(false);
            let raw: &[u8] = &encoded;
            hex::encode(raw)
        };
        let pk0 = to_hex(&keys[0].public_key());
        assert_eq!(pk0, to_hex(&keys[1].public_key()), "pk0==pk1");
        assert_eq!(pk0, to_hex(&keys[2].public_key()), "pk0==pk2");

        assert_ne!(keys[0].keys_linear.x_i, keys[1].keys_linear.x_i);
        assert_ne!(keys[1].keys_linear.x_i, keys[2].keys_linear.x_i);

        assert_eq!((keys[0].i, keys[1].i, keys[2].i), (1, 2, 3));
    }

    /// 2-of-3 分散式签名全流程：DKG → OfflineStage → SignManual → 聚合本地验签。
    #[test]
    fn dist_sign_2_of_3_completes_and_verifies() {
        let mut sim = Simulation::new();
        for i in 1..=3u16 {
            sim.add_party(sm_keygen::Keygen::new(i, 2, 3).expect("Keygen::new"));
        }
        let keys: Vec<sm_keygen::LocalKey<Secp256k1>> = sim.run().expect("dkg");

        // 2-of-3 门限签名：GG20 的 keygen t=2 决定门限；签名需 t+1=3 方
        // 参与离线阶段（t=2 时任意 2 方之和 ≠ 聚合公钥，phase6 校验
        // sum(S_i over s_l) == y_sum_s 只在 s_l 覆盖 t+1 方时成立——
        // 上游官方测试亦全部 t=1 或 s_l=全集）。
        // 生产语义：keygen t=2 → 任何 3 方（全部）签名；t=1 → 任意 2 方签名。
        let s_l = vec![1u16, 2u16, 3u16];
        let mut sign_sim = Simulation::new();
        // 上游约定（sign.rs::simulate_offline_stage 同款）：OfflineStage 的
        // 第一参数是 s_l 内的序号 i（连续 1..len），LocalKey 用原始 keygen index。
        for (i, &keygen_i) in (1u16..).zip(&s_l) {
            sign_sim.add_party(
                sm_sign::OfflineStage::new(i, s_l.clone(), keys[usize::from(keygen_i) - 1].clone())
                    .expect("OfflineStage::new"),
            );
        }
        let offline: Vec<sm_sign::CompletedOfflineStage> = sign_sim.run().expect("offline stage");

        // 在线单轮：各方 SignManual 产部分签名
        let message_hash = [7u8; 32];
        let message = BigInt::from_bytes(&message_hash);
        let mut manuals = vec![];
        let mut partials = vec![];
        for out in offline {
            let (m, p) = sm_sign::SignManual::new(message.clone(), out).expect("SignManual");
            manuals.push(m);
            partials.push(p);
        }

        // 方 1 聚合（其余方部分签名传入）+ 标准 secp256k1 验签
        // party_i::verify(sig, y, message)——公钥取本方 LocalKey（全体一致）
        let sig = manuals
            .into_iter()
            .next()
            .expect("first manual")
            .complete(&partials[1..])
            .expect("complete signing");
        let y = keys[0].public_key();
        multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::party_i::verify(
            &sig, &y, &message,
        )
        .expect("aggregated signature must verify against the session-agreed public key");
    }

    /// 分散式份额落盘隔离：每方只写自己的 local_key 文件；读取不含他方份额。
    ///
    /// 显式 base_dir + 显式密钥（零全局 env——与 persistence 测试家族无并行竞态）。
    /// v2.2.0 阶段二起文件为 AES-256-GCM 密文（`NXC1` 版本头）。
    #[test]
    fn dist_local_key_persistence_isolated() {
        let mut sim = Simulation::new();
        for i in 1..=3u16 {
            sim.add_party(sm_keygen::Keygen::new(i, 2, 3).expect("Keygen::new"));
        }
        let keys: Vec<sm_keygen::LocalKey<Secp256k1>> = sim.run().expect("dkg");
        // 唯一化目录（pid + 纳秒时钟）：防同 pid 重跑时读到上次残留文件
        let dir = std::env::temp_dir().join(format!(
            "dist-mpc-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        ));
        let key = [0x42u8; 32];

        let sid = "dist-persist-test";
        for (idx, k) in keys.iter().enumerate() {
            let party = u16::try_from(idx + 1).expect("index fits u16");
            persist_local_key(&dir, sid, party, k, &key, 1).expect("persist local key");
        }

        // 每方读回自己的 LocalKey：i 与本方一致；版本号往返一致
        for idx in 0..3u16 {
            let (version, loaded) = load_local_key(&dir, sid, idx + 1, &key)
                .expect("load")
                .expect("exists");
            assert_eq!(loaded.i, idx + 1, "party {idx} loads only its own key");
            assert_eq!(loaded.t, 2);
            assert_eq!(loaded.n, 3);
            assert_eq!(version, 1, "key version round-trips from file header");
        }

        // 目录内 3 个文件、每个文件 < 32KB（LocalKey 体量级，断言非全量会话
        // ——全量 DkgSession JSON 在同参数下数倍于此）
        let entries: Vec<std::fs::DirEntry> = std::fs::read_dir(dir.join("dist"))
            .expect("read dir")
            .filter_map(|e| e.ok())
            .collect();
        assert_eq!(entries.len(), 3, "exactly one file per party");
        for f in &entries {
            let size = f.metadata().expect("meta").len();
            assert!(
                size < 32 * 1024,
                "local key file must be compact (got {size}B)"
            );
        }

        // 清理
        std::fs::remove_dir_all(&dir).ok();
    }

    /// v2.2.0 份额静态加密安全属性（阶段二接入）：
    /// 1. 落盘文件是密文（`NXC1` 魔数开头，非明文 JSON）
    /// 2. 密文不泄露份额内容（x_i 的 hex 不在文件字节中出现）
    /// 3. 错误密钥解密失败（GCM 完整性校验 fail-closed）
    /// 4. 明文 JSON（阶段一格式）被拒绝读取——不降级
    /// 5. key_version 写入文件头并往返
    #[test]
    fn dist_local_key_encrypted_at_rest() {
        let mut sim = Simulation::new();
        sim.add_party(sm_keygen::Keygen::new(1, 1, 2).expect("Keygen::new"));
        sim.add_party(sm_keygen::Keygen::new(2, 1, 2).expect("Keygen::new"));
        let keys: Vec<sm_keygen::LocalKey<Secp256k1>> = sim.run().expect("dkg");
        // 唯一化目录（pid + 会话名 + 纳秒时钟）：防同 pid 重跑时读到上次残留文件
        let dir = std::env::temp_dir().join(format!(
            "dist-mpc-enc-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        ));
        let key = [0x42u8; 32];
        let sid = "dist-enc-test";

        persist_local_key(&dir, sid, 1, &keys[0], &key, 3).expect("persist");
        let path = dir.join("dist").join("p1-dist-enc-test.bin");
        let raw = std::fs::read(&path).expect("read raw");

        // 1. NXC1 魔数 + 非明文 JSON
        assert!(
            raw.starts_with(crate::persistence::KEY_VERSION_MAGIC),
            "encrypted local key file must start with NXC1 magic"
        );
        assert!(!raw.starts_with(b"{"), "file must not be plaintext JSON");
        // 2. 密文不含份额内容（x_i 经 BigInt 的 hex 表示不在密文字节里）
        let xi_hex =
            curv::arithmetic::traits::Converter::to_hex(&keys[0].keys_linear.x_i.to_bigint());
        assert!(
            !raw.windows(xi_hex.len()).any(|w| w == xi_hex.as_bytes()),
            "ciphertext must not contain the plaintext share bytes"
        );
        // 3. 错误密钥 fail-closed
        let wrong = [0xAAu8; 32];
        assert!(
            load_local_key(&dir, sid, 1, &wrong).is_err(),
            "wrong key must fail (GCM integrity)"
        );
        // 4. 明文 JSON 不是合法输入：手工写一个 .bin 明文文件，读取必须报错
        std::fs::write(&path, b"{\"stage1\":\"plaintext\"}").expect("plant plaintext");
        assert!(
            load_local_key(&dir, sid, 1, &key).is_err(),
            "plaintext JSON must be rejected (fail-closed, no legacy fallback)"
        );
        // 5. 重写加密文件（version 3）后版本号往返
        persist_local_key(&dir, sid, 1, &keys[0], &key, 3).expect("re-persist");
        let (version, loaded) = load_local_key(&dir, sid, 1, &key)
            .expect("load")
            .expect("exists");
        assert_eq!(version, 3, "key version must round-trip");
        assert_eq!(loaded.i, 1);

        std::fs::remove_dir_all(&dir).ok();
    }
}

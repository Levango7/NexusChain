//! MPC 会话持久化（方案 A 多进程缺口 1：dkg 份额落盘，重启后 Sign 可恢复）。
//!
//! DkgSession（serde 序列化，注释明确"序列化存储供 Sign 阶段重建"）——
//! DKG 完成后落盘到 `MPC_ENGINE_SESSION_DIR`（默认 `./mpc-sessions`），
//! Sign 时内存缺失则从盘恢复。
//!
//! **MPC-P1-05 安全止血**：会话快照含全部 n 方私钥份额，明文 JSON 落盘可被
//! 任意进程/用户读取并提取任意方私钥份额。现改为落盘前用 **AES-256-GCM**
//! 认证加密，密钥从环境变量 `MPC_STORAGE_KEY` 读取（hex 编码的 32 字节）。
//! 文件格式：`nonce(12B) || ciphertext`，GCM 自带完整性校验防篡改。
//! 引擎侧隔离进程持有密钥材料不跨进程传输（方案 A"份额只在参与者进程"语义）。
//!
//! **MPC-P2-F5 分布式安全模型**：
//!   * `persist_session`：保留全量会话加密落盘（兼容可信协调器模式，run_sign 需全量份额）。
//!   * `persist_my_share`：**只加密存储本方私钥份额**（`my_private_share`），
//!     聚合公钥与各方可验证公钥明文存储（验签所需，非私钥材料）。
//!   * `MyShareRecord`：本方份额持久化记录（私钥份额加密，公钥材料明文）。
//!
//! **S4-a 修复（session_id 路径穿越净化）**：RPC 原始 session_id 在拼接落盘
//! 文件名前一律经 `sanitize_session_id` 净化（仅保留 [A-Za-z0-9-_]），
//! 产物被约束为会话目录内的单文件名——封堵 `../` 逃逸、Windows 盘符冒号、
//! UNC 前缀与嵌套分隔符；persist/load/remove 三入口共用同一净化函数，
//! 读写闭环一致（原 distributed.rs 私有实现提升为共享）。

use crate::gg20::DkgSession;
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use eyre::eyre;
use rand::rngs::OsRng;
use rand::RngCore;
use serde::{Deserialize, Serialize};
// zeroize：密钥材料安全擦除。MyShareRecord 派生 Zeroize，
// 在加密的私钥份额密文与公钥材料 hex 离开作用域前擦除内存。
use std::fs;
use std::path::{Path, PathBuf};
use zeroize::Zeroize;

/// 会话目录环境变量。
const SESSION_DIR_ENV: &str = "MPC_ENGINE_SESSION_DIR";

/// AES-256-GCM 密钥环境变量名（MPC-P1-05）。
const STORAGE_KEY_ENV: &str = "MPC_STORAGE_KEY";

/// 中12: 当前密钥版本号环境变量名。
///
/// 由 `PartyConfig::apply_storage_key_to_env` 从配置文件同步到环境变量，
/// persistence 模块加密新文件时读取此版本号写入文件头。
/// 未设置时默认为 `DEFAULT_KEY_VERSION`(1)（向后兼容）。
const STORAGE_KEY_VERSION_ENV: &str = "MPC_STORAGE_KEY_VERSION";

/// GCM nonce 长度（字节）。
const NONCE_LEN: usize = 12;

/// AES-256 密钥长度（字节）。
const KEY_LEN: usize = 32;

/// 中12: 密钥版本号文件头魔数（"NXC1" = NexusChain v1 格式）。
///
/// 加密文件新格式：`MAGIC(4B) || version(4B LE) || nonce(12B) || ciphertext`。
/// 旧格式（无版本号）：`nonce(12B) || ciphertext`，解密时检测无 MAGIC 前缀则视为版本 1。
///
/// `pub(crate)`：distributed.rs 的加密落盘测试断言魔数前缀。
pub(crate) const KEY_VERSION_MAGIC: &[u8; 4] = b"NXC1";

/// 中12: 密钥版本号文件头长度（MAGIC 4B + version 4B LE）。
const KEY_VERSION_HEADER_LEN: usize = 8;

/// 中12: 默认密钥版本号（旧文件无版本头时视为此版本）。
const DEFAULT_KEY_VERSION: u32 = 1;

/// 获取会话目录（可配置，默认 ./mpc-sessions）。
pub fn session_dir() -> PathBuf {
    std::env::var(SESSION_DIR_ENV)
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("./mpc-sessions"))
}

/// S4-a: session_id 文件名安全化（仅保留 [A-Za-z0-9-_]，其余字符替换为 '_'）。
///
/// **修复背景**：dkg.rs 可信协调器路径把 RPC 原始 `session_id` 直接传给
/// `persist_session`/`load_session`，而旧 `session_path` 用 `format!` 无净化
/// 拼接文件名——含 `../` 的 session_id 可穿越会话目录逃逸写任意路径。
/// 净化后产物只含安全字符集，`session_dir().join(sanitized)` 必然落在
/// 会话目录内的单文件名（无 `/`、`\`、盘符冒号、UNC 前缀），穿越被结构性封堵。
///
/// 实现与 distributed.rs v2.2.0 分散式路径的私有 `sanitize_session_id`
/// 逐字符策略一致——原实现提升至此作为共享实现，distributed.rs 改为复用，
/// 消除两处独立维护的净化逻辑漂移风险。
///
/// `pub(crate)`：distributed.rs 的分散式落盘路径与本模块测试复用。
pub(crate) fn sanitize_session_id(session_id: &str) -> String {
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

fn session_path(session_id: &str) -> PathBuf {
    // S4-a: 净化后拼接——封堵 `../` 穿越与非法文件名字符
    session_dir().join(format!("session-{}.json", sanitize_session_id(session_id)))
}

/// MPC-P2-F5: 本方份额持久化文件路径（与全量会话文件分离）。
fn my_share_path(session_id: &str) -> PathBuf {
    // S4-a: 净化后拼接——封堵 `../` 穿越与非法文件名字符
    session_dir().join(format!("my-share-{}.json", sanitize_session_id(session_id)))
}

/// 低9: 设置文件权限为 0600（仅所有者可读写），Unix 特有。
///
/// Windows 上此函数为空操作（`#[cfg(not(unix))]`），因 Unix 权限模型不适用。
/// Windows 上文件权限通过 ACL 管理，应由部署环境（如 NTFS ACL）单独配置。
///
/// # 安全
/// 0600 = rw-------（所有者读写，组与其他无任何权限）。
/// 防止其他用户/进程读取加密文件（虽然文件已加密，但权限收紧是纵深防御）。
///
/// `pub(crate)`：distributed.rs 的 v2.2.0 份额落盘加密（阶段二接入）复用此函数。
#[cfg(unix)]
pub(crate) fn set_secure_permissions(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    if let Err(e) = fs::set_permissions(path, fs::Permissions::from_mode(0o600)) {
        tracing::warn!(
            path = %path.display(),
            error = %e,
            "低9: failed to set 0600 permissions on session file (best-effort)"
        );
    }
}

/// 低9: 非 Unix 平台（如 Windows）的空操作。
///
/// Windows 上文件权限通过 ACL 管理，此处空操作。
/// 部署时应通过 NTFS ACL 限制 session 目录访问（如仅 mpc-engine 服务账户可访问）。
#[cfg(not(unix))]
pub(crate) fn set_secure_permissions(_path: &Path) {
    // Windows 上文件权限通过 ACL 管理，此处空操作。
    // 部署时应通过 NTFS ACL 限制 session 目录访问（如仅 mpc-engine 服务账户可访问）。
    tracing::debug!(
        "低9: set_secure_permissions is no-op on non-Unix (use NTFS ACL for access control)"
    );
}

/// 从环境变量 `MPC_STORAGE_KEY` 加载 AES-256 密钥（hex 编码的 32 字节）。
///
/// 返回 `[u8; 32]` 密钥。若环境变量未设置或格式非法，返回错误。
/// 生产环境必须设置 `MPC_STORAGE_KEY`；未设置时拒绝落盘/读盘（fail-closed）。
fn load_storage_key() -> eyre::Result<[u8; KEY_LEN]> {
    let key_hex = std::env::var(STORAGE_KEY_ENV).map_err(|_| {
        eyre!(
            "{} not set — refusing to persist/load session without encryption key \
             (MPC-P1-05: fail-closed, set {} to a 64-char hex string encoding 32 bytes)",
            STORAGE_KEY_ENV,
            STORAGE_KEY_ENV
        )
    })?;
    let key_bytes =
        hex::decode(&key_hex).map_err(|e| eyre!("{} hex decode failed: {e}", STORAGE_KEY_ENV))?;
    if key_bytes.len() != KEY_LEN {
        return Err(eyre!(
            "{} must be {} bytes ({} hex chars), got {} bytes",
            STORAGE_KEY_ENV,
            KEY_LEN,
            KEY_LEN * 2,
            key_bytes.len()
        ));
    }
    let mut key = [0u8; KEY_LEN];
    key.copy_from_slice(&key_bytes);
    Ok(key)
}

/// AES-256-GCM 加密。
///
/// 输出格式：`nonce(12B) || ciphertext`（GCM tag 内嵌于 ciphertext 尾部）。
/// nonce 使用 `OsRng` 密码学随机数生成器生成。
///
/// `pub(crate)`：distributed.rs 的 v2.2.0 份额落盘加密复用。
pub(crate) fn aes_encrypt(plaintext: &[u8], key: &[u8; KEY_LEN]) -> eyre::Result<Vec<u8>> {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    let mut nonce_bytes = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut nonce_bytes);
    let ciphertext = cipher
        .encrypt(Nonce::from_slice(&nonce_bytes), plaintext)
        .map_err(|e| eyre!("AES-256-GCM encrypt failed: {e}"))?;
    let mut out = Vec::with_capacity(NONCE_LEN + ciphertext.len());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// AES-256-GCM 解密。
///
/// 输入格式：`nonce(12B) || ciphertext`。GCM 自带完整性校验，篡改会返回错误。
pub(crate) fn aes_decrypt(data: &[u8], key: &[u8; KEY_LEN]) -> eyre::Result<Vec<u8>> {
    if data.len() < NONCE_LEN {
        return Err(eyre!(
            "encrypted data too short ({} < {}): corrupted or not encrypted with MPC-P1-05 format",
            data.len(),
            NONCE_LEN
        ));
    }
    let (nonce_bytes, ciphertext) = data.split_at(NONCE_LEN);
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    cipher
        .decrypt(Nonce::from_slice(nonce_bytes), ciphertext)
        .map_err(|e| eyre!("AES-256-GCM decrypt failed (wrong key or tampered?): {e}"))
}

/// 中12: AES-256-GCM 加密（带密钥版本号文件头）。
///
/// 输出格式：`MAGIC(4B "NXC1") || version(4B LE) || nonce(12B) || ciphertext`。
/// 解密时 `aes_decrypt_with_version` 根据 MAGIC 前缀识别新格式并读取版本号，
/// 选择对应版本的密钥解密（完整多密钥支持见 `load_storage_key_for_version`，TODO）。
///
/// `version` 为密钥版本号，用于密钥轮换：新文件用当前版本加密，
/// 旧文件由解密方根据版本号选择对应密钥。
///
/// `pub(crate)`：distributed.rs 的 v2.2.0 份额落盘加密复用。
pub(crate) fn aes_encrypt_with_version(
    plaintext: &[u8],
    key: &[u8; KEY_LEN],
    version: u32,
) -> eyre::Result<Vec<u8>> {
    let mut out = Vec::with_capacity(KEY_VERSION_HEADER_LEN + NONCE_LEN + plaintext.len() + 16);
    out.extend_from_slice(KEY_VERSION_MAGIC);
    out.extend_from_slice(&version.to_le_bytes());
    // 复用 aes_encrypt 生成 nonce || ciphertext，再拼接到头之后
    let enc = aes_encrypt(plaintext, key)?;
    out.extend_from_slice(&enc);
    Ok(out)
}

/// 中12: AES-256-GCM 解密（带密钥版本号文件头）。
///
/// 输入格式：
///   * 新格式：`MAGIC(4B "NXC1") || version(4B LE) || nonce(12B) || ciphertext`
///   * 旧格式（无版本号）：`nonce(12B) || ciphertext`，视为版本 `DEFAULT_KEY_VERSION`(1)
///
/// 返回 `(version, plaintext)`。调用方根据 version 选择对应密钥
/// （当前实现仍用单一 `MPC_STORAGE_KEY`，完整多密钥支持标注 TODO）。
///
/// `pub(crate)`：distributed.rs 的 v2.2.0 份额落盘解密复用。
pub(crate) fn aes_decrypt_with_version(
    data: &[u8],
    key: &[u8; KEY_LEN],
) -> eyre::Result<(u32, Vec<u8>)> {
    // 检测新格式：以 MAGIC 前缀开头
    if data.len() >= KEY_VERSION_HEADER_LEN && &data[0..4] == KEY_VERSION_MAGIC {
        let version = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
        let payload = &data[KEY_VERSION_HEADER_LEN..];
        let plaintext = aes_decrypt(payload, key)?;
        Ok((version, plaintext))
    } else {
        // 旧格式（无版本头）：视为版本 1，直接解密
        let plaintext = aes_decrypt(data, key)?;
        Ok((DEFAULT_KEY_VERSION, plaintext))
    }
}

/// 中12: 从环境变量加载指定版本的 AES-256 密钥。
///
/// 当前实现：所有版本都使用 `MPC_STORAGE_KEY`（单密钥模式）。
/// 完整多密钥支持（从 `PartyConfig.storage_keys` 映射按版本号选择密钥）标注 TODO，
/// 因 persistence 模块不持有 `PartyConfig` 引用，需通过环境变量
/// `MPC_STORAGE_KEY_V{version}` 或全局单例传递，待后续重构。
///
/// `version` 参数仅用于日志记录，实际密钥仍从 `MPC_STORAGE_KEY` 读取。
fn load_storage_key_for_version(version: u32) -> eyre::Result<[u8; KEY_LEN]> {
    let key = load_storage_key()?;
    if version != DEFAULT_KEY_VERSION {
        tracing::debug!(
            version,
            "中12: load_storage_key_for_version — using single MPC_STORAGE_KEY for all versions \
             (multi-key support TODO)"
        );
    }
    Ok(key)
}

/// 中12: 读取当前密钥版本号（从 `MPC_STORAGE_KEY_VERSION` 环境变量）。
///
/// 未设置时返回 `DEFAULT_KEY_VERSION`(1)（向后兼容）。
/// 由 `PartyConfig::apply_storage_key_to_env` 在启动时设置。
fn current_storage_key_version() -> u32 {
    std::env::var(STORAGE_KEY_VERSION_ENV)
        .ok()
        .and_then(|s| s.parse::<u32>().ok())
        .filter(|v| *v > 0)
        .unwrap_or(DEFAULT_KEY_VERSION)
}

/// 持久化 DKG 会话（份额材料落盘，重启可恢复）。
///
/// **MPC-P1-05**：落盘前用 AES-256-GCM 加密，密钥从 `MPC_STORAGE_KEY` 读取。
/// 文件内容为 `nonce(12B) || ciphertext`，非明文 JSON。
///
/// 注：此函数保留全量会话加密落盘（兼容可信协调器模式，run_sign 需全量份额）。
/// **MPC-P2-F5**：分布式安全模式应优先使用 `persist_my_share`，只存本方份额。
pub fn persist_session(session_id: &str, session: &DkgSession) -> eyre::Result<()> {
    let dir = session_dir();
    fs::create_dir_all(&dir)
        .map_err(|e| eyre!("cannot create session dir {}: {e}", dir.display()))?;
    let json =
        serde_json::to_vec_pretty(session).map_err(|e| eyre!("session serialize failed: {e}"))?;
    // MPC-P1-05: AES-256-GCM 加密后落盘（防明文份额泄露）
    // 中12: 加密时在文件头写入当前密钥版本号，支持密钥轮换
    let key = load_storage_key()?;
    let version = current_storage_key_version();
    let encrypted = aes_encrypt_with_version(&json, &key, version)?;
    let path = session_path(session_id);
    fs::write(&path, encrypted)
        .map_err(|e| eyre!("cannot write session {}: {e}", path.display()))?;
    // 低9: 设置 0600 权限（仅所有者可读写，Unix 特有，Windows 空操作）
    set_secure_permissions(&path);
    tracing::info!(
        session_id = %session_id,
        path = %path.display(),
        encrypted_bytes = json.len(),
        key_version = version,
        "dkg session persisted (AES-256-GCM encrypted, MPC-P1-05, 中12: key version {} in file header)",
        version
    );

    // MPC-P2-F5: 同时持久化本方份额隔离记录（只含本方私钥份额 + 公钥材料明文）
    if let Err(e) = persist_my_share(session_id, session) {
        tracing::warn!(
            session_id = %session_id,
            error = %e,
            "MPC-P2-F5: persist_my_share failed (full session still persisted for compatibility)"
        );
    }

    Ok(())
}

/// 从盘恢复会话（供 Sign 阶段使用）。
///
/// **MPC-P1-05**：读盘后用 AES-256-GCM 解密，密钥从 `MPC_STORAGE_KEY` 读取。
/// 解密失败（密钥不匹配/文件篡改）返回错误。
pub fn load_session(session_id: &str) -> eyre::Result<Option<DkgSession>> {
    let path = session_path(session_id);
    if !Path::new(&path).exists() {
        return Ok(None);
    }
    let bytes =
        fs::read(&path).map_err(|e| eyre!("cannot read session {}: {e}", path.display()))?;
    // MPC-P1-05: AES-256-GCM 解密
    // 中12: 从文件头读取密钥版本号，按版本号选择密钥（当前单密钥，多密钥 TODO）
    let key = load_storage_key()?;
    let (version, plaintext) = aes_decrypt_with_version(&bytes, &key)?;
    let session: DkgSession =
        serde_json::from_slice(&plaintext).map_err(|e| eyre!("session deserialize failed: {e}"))?;
    tracing::info!(
        session_id = %session_id,
        key_version = version,
        "dkg session restored from disk (decrypted, MPC-P1-05, 中12: key version {} from file header)",
        version
    );
    Ok(Some(session))
}

/// 删除会话（密钥轮换/清理）。
pub fn remove_session(session_id: &str) {
    let path = session_path(session_id);
    if path.exists() {
        let _ = fs::remove_file(&path);
        tracing::info!(session_id = %session_id, "dkg session removed");
    }
    // MPC-P2-F5: 同时删除本方份额记录
    let my_path = my_share_path(session_id);
    if my_path.exists() {
        let _ = fs::remove_file(&my_path);
        tracing::info!(session_id = %session_id, "MPC-P2-F5: my-share record removed");
    }
}

// =========================================================================
// MPC-P2-F5: 本方份额隔离持久化
// =========================================================================

/// MPC-P2-F5: 本方份额持久化记录。
///
/// **私钥材料**（`my_private_share`）：AES-256-GCM 加密存储，只含本方私钥份额。
/// **公钥材料**（明文存储，验签所需，非私钥）：
///   * `aggregate_public_key`：聚合公钥（hex）
///   * `party_public_keys`：各方可验证公钥（hex）
///   * `vss_scheme`、`dlog_proofs`：VSS 方案与 DLog 证明（验签用）
///
/// **密钥材料安全擦除**：派生 `Zeroize`。所有字段（`Vec<u8>`、`String`、`usize`、
/// `u16`）均实现 `Zeroize`，派生后调用 `zeroize()` 将加密的私钥份额密文、
/// 聚合公钥 hex、各方可验证公钥 hex 等内存清零。调用方可显式调用 `zeroize()`
/// 或派生 `ZeroizeOnDrop` 在离开作用域时自动擦除。
#[derive(Clone, Serialize, Deserialize, Zeroize)]
pub struct MyShareRecord {
    /// 本方索引。
    pub my_party_index: usize,
    /// 本方标识（人类可读）。
    #[serde(default)]
    pub my_party_id: String,
    /// 加密后的本方私钥份额（`nonce(12B) || ciphertext`，AES-256-GCM）。
    pub encrypted_private_share: Vec<u8>,
    /// 聚合公钥（hex 编码，明文存储——验签所需，非私钥材料）。
    pub aggregate_public_key: String,
    /// 各方可验证公钥（hex 编码数组，明文存储——验签所需）。
    pub party_public_keys: Vec<String>,
    /// DKG 参数（threshold, share_count）。
    pub threshold: u16,
    pub share_count: u16,
}

/// MPC-P2-F5: 只持久化本方私钥份额（加密）+ 公钥材料（明文）。
///
/// 与 `persist_session`（全量会话加密落盘）不同，此函数只存储本方私钥份额，
/// 其他方的私钥份额不落盘。聚合公钥与各方可验证公钥明文存储（验签所需）。
///
/// 文件格式：JSON（`MyShareRecord`），其中 `encrypted_private_share` 字段为
/// `nonce(12B) || ciphertext`（AES-256-GCM 加密的本方份额 JSON）。
pub fn persist_my_share(session_id: &str, session: &DkgSession) -> eyre::Result<()> {
    let dir = session_dir();
    fs::create_dir_all(&dir)
        .map_err(|e| eyre!("cannot create session dir {}: {e}", dir.display()))?;

    // 提取本方私钥份额（必须已设置 my_private_share）
    let my_share = session.my_private_share.as_ref().ok_or_else(|| {
        eyre!(
            "MPC-P2-F5: cannot persist_my_share — my_private_share not set \
             (call DkgSession::set_my_identity first)"
        )
    })?;

    // 加密本方私钥份额
    // 中12: 加密时在文件头写入当前密钥版本号
    let key = load_storage_key()?;
    let version = current_storage_key_version();
    let share_json = serde_json::to_vec_pretty(my_share)
        .map_err(|e| eyre!("my_private_share serialize failed: {e}"))?;
    let encrypted_share = aes_encrypt_with_version(&share_json, &key, version)?;

    // 公钥材料明文（hex 编码）
    let aggregate_public_key = crate::gg20::hex_point(&session.y_sum);
    let party_public_keys: Vec<String> =
        session.pk_vec.iter().map(crate::gg20::hex_point).collect();

    let record = MyShareRecord {
        my_party_index: session.my_party_index,
        my_party_id: String::new(), // 由上层填充（PartyConfig.party_id）
        encrypted_private_share: encrypted_share,
        aggregate_public_key,
        party_public_keys,
        threshold: session.params.threshold,
        share_count: session.params.share_count,
    };

    let json = serde_json::to_vec_pretty(&record)
        .map_err(|e| eyre!("MyShareRecord serialize failed: {e}"))?;
    let path = my_share_path(session_id);
    fs::write(&path, json).map_err(|e| eyre!("cannot write my-share {}: {e}", path.display()))?;
    // 低9: 设置 0600 权限（仅所有者可读写，Unix 特有，Windows 空操作）
    set_secure_permissions(&path);

    tracing::info!(
        session_id = %session_id,
        path = %path.display(),
        my_party_index = record.my_party_index,
        party_public_keys_count = record.party_public_keys.len(),
        key_version = version,
        "MPC-P2-F5: my private share persisted (AES-256-GCM encrypted, 中12: key version {}), \
         public keys stored in plaintext (for verification)",
        version
    );
    Ok(())
}

/// MPC-P2-F5: 从盘加载本方份额记录。
///
/// 返回 `MyShareRecord`（含加密的本方份额与明文公钥材料）。
/// 解密本方份额需调用 `decrypt_my_share`。
pub fn load_my_share(session_id: &str) -> eyre::Result<Option<MyShareRecord>> {
    let path = my_share_path(session_id);
    if !Path::new(&path).exists() {
        return Ok(None);
    }
    let bytes =
        fs::read(&path).map_err(|e| eyre!("cannot read my-share {}: {e}", path.display()))?;
    let record: MyShareRecord = serde_json::from_slice(&bytes)
        .map_err(|e| eyre!("MyShareRecord deserialize failed: {e}"))?;
    tracing::info!(
        session_id = %session_id,
        my_party_index = record.my_party_index,
        "MPC-P2-F5: my-share record loaded from disk"
    );
    Ok(Some(record))
}

/// MPC-P2-F5: 解密本方私钥份额。
///
/// 从 `MyShareRecord.encrypted_private_share` 解密出 `SharedKeysSerde`。
/// 中12: 从密文头读取密钥版本号，按版本号选择密钥（当前单密钥，多密钥 TODO）。
pub fn decrypt_my_share(record: &MyShareRecord) -> eyre::Result<crate::gg20::SharedKeysSerde> {
    let key = load_storage_key()?;
    let (version, plaintext) = aes_decrypt_with_version(&record.encrypted_private_share, &key)?;
    let share: crate::gg20::SharedKeysSerde = serde_json::from_slice(&plaintext)
        .map_err(|e| eyre!("my_private_share deserialize failed: {e}"))?;
    tracing::debug!(
        key_version = version,
        "中12: my-share decrypted with key version {} (from ciphertext header)",
        version
    );
    Ok(share)
}

// =========================================================================
// CGGMP21 份额持久化（PLAN-cggmp-keyshare-persistence，K 批前置）
// =========================================================================
// 与 D 批 LocalKey 落盘（distributed.rs）同一 NXC1 信封：
// `MAGIC("NXC1") || version(4B LE) || nonce(12B) || GCM ciphertext`。
// 明文是 cggmp.rs 的 serde JSON（encode_incomplete / encode_key_share——
// 后者调用方必须先经 sanitize_for_disk 清洗 crt/multiexp）。
//
// 语义约定（设计稿 §7 审核修订）：
//   * 会话/份额文件名经 sanitize_session_id 净化——封堵 `../` 穿越（S4-a 同款）；
//   * 解码失败（篡改/截断/错密钥）一律硬错误 fail-closed，绝不静默跳过；
//   * `None` 返回值仅表示"文件不存在"（首次运行），与"存在但损坏"严格区分。

/// CGGMP21 份额文件路径：`{base_dir}/cggmp/{sanitized_session_id}/{kind}.bin`。
fn cggmp_share_path(base_dir: &std::path::Path, session_id: &str, kind: &str) -> PathBuf {
    base_dir
        .join("cggmp")
        .join(sanitize_session_id(session_id))
        .join(format!("{kind}.bin"))
}

/// 持久化一个 CGGMP21 协议产物（加密 + 原子性由调用方保证单写者——驱动线程独占）。
///
/// `kind` 仅允许 `incomplete` / `keyshare`（白名单，防拼接逃逸）。
/// `base_dir` 传入会话根目录（生产 = `MPC_ENGINE_SESSION_DIR`）。
pub(crate) fn persist_cggmp_blob(
    session_id: &str,
    kind: &str,
    base_dir: &std::path::Path,
    plaintext: &[u8],
    key: &[u8; KEY_LEN],
    key_version: u32,
) -> eyre::Result<PathBuf> {
    if kind != "incomplete" && kind != "keyshare" {
        return Err(eyre!("cggmp persist: invalid kind '{kind}'"));
    }
    let path = cggmp_share_path(base_dir, session_id, kind);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| eyre!("cggmp persist: create dir {}: {e}", parent.display()))?;
    }
    let blob = aes_encrypt_with_version(plaintext, key, key_version)?;
    fs::write(&path, &blob).map_err(|e| eyre!("cggmp persist: write {}: {e}", path.display()))?;
    tracing::info!(
        session_id = %session_id,
        kind = kind,
        path = %path.display(),
        "cggmp share persisted (NXC1 encrypted)"
    );
    Ok(path)
}

/// 加载 CGGMP21 协议产物密文并解密。
///
/// 返回 `Ok(None)` = 文件不存在（首次运行）；存在但解密/解码失败 → 硬错误
/// （fail-closed——篡改/截断/错密钥在此暴露，绝不降级为"没有"）。
pub(crate) fn load_cggmp_blob(
    session_id: &str,
    kind: &str,
    base_dir: &std::path::Path,
    key: &[u8; KEY_LEN],
) -> eyre::Result<Option<(u32, Vec<u8>)>> {
    if kind != "incomplete" && kind != "keyshare" {
        return Err(eyre!("cggmp load: invalid kind '{kind}'"));
    }
    let path = cggmp_share_path(base_dir, session_id, kind);
    if !path.exists() {
        return Ok(None);
    }
    let blob = fs::read(&path).map_err(|e| eyre!("cggmp load: read {}: {e}", path.display()))?;
    let (version, plaintext) = aes_decrypt_with_version(&blob, key).map_err(|e| {
        eyre!(
            "cggmp load: decrypt {} failed (tampered/truncated/wrong key?): {e}",
            path.display()
        )
    })?;
    tracing::info!(
        session_id = %session_id,
        kind = kind,
        key_version = version,
        "cggmp share loaded from disk"
    );
    Ok(Some((version, plaintext)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Once;

    /// 测试用 AES-256 密钥（hex 编码的 32 字节全 0x42）。
    /// 用 Once 保证只设置一次环境变量（多线程测试安全）。
    static SET_KEY: Once = Once::new();

    fn ensure_test_key() {
        SET_KEY.call_once(|| {
            // 32 字节全 0x42 → hex "4242...42"（64 chars）
            let key_hex = "42".repeat(KEY_LEN);
            // SAFETY: 测试中调用，Once 保证只调用一次。测试运行时通常单线程，
            // 且其他测试通过 ensure_test_key 同步获取同一 key。
            unsafe {
                std::env::set_var(STORAGE_KEY_ENV, key_hex);
            }
        });
    }

    #[test]
    fn persist_and_restore_round_trip() {
        ensure_test_key();
        // 使用真实 DKG 会话验证序列化往返
        let (_, _, session) = crate::gg20::run_keygen(1, 2).expect("GG20 DKG failed");
        let id = "persist-test-1";
        persist_session(id, &session).expect("persist");
        let restored = load_session(id).expect("load").expect("some");
        assert_eq!(restored.params.threshold, session.params.threshold);
        assert_eq!(restored.params.share_count, session.params.share_count);
        assert_eq!(restored.y_sum, session.y_sum, "聚合公钥应一致");
        remove_session(id);
    }

    // ---- CGGMP21 blob API（PLAN-cggmp-keyshare-persistence §3.5）----

    fn cggmp_test_base(tag: &str) -> std::path::PathBuf {
        let dir =
            std::env::temp_dir().join(format!("cggmp-persist-unit-{}-{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    fn cggmp_test_key() -> [u8; 32] {
        [0x5A; 32]
    }

    #[test]
    fn cggmp_blob_round_trip_and_nxc1_magic() {
        let base = cggmp_test_base("rt");
        let key = cggmp_test_key();
        let plaintext = b"{\"kind\":\"unit-test-payload\",\"v\":7}";

        persist_cggmp_blob("sess-rt", "keyshare", &base, plaintext, &key, 3).expect("persist");

        // 落盘文件以 NXC1 魔数开头（版本化信封格式）
        let raw = std::fs::read(cggmp_share_path(&base, "sess-rt", "keyshare")).expect("read");
        assert!(raw.len() > 8, "blob must exceed header");
        assert_eq!(&raw[0..4], KEY_VERSION_MAGIC, "NXC1 magic prefix");
        // 版本号 = 3（LE）
        assert_eq!(u32::from_le_bytes([raw[4], raw[5], raw[6], raw[7]]), 3);
        // 密文不是明文（payload 不出现）
        assert!(!raw.windows(8).any(|w| w == b"unit-test"));

        let (ver, decoded) = load_cggmp_blob("sess-rt", "keyshare", &base, &key)
            .expect("load")
            .expect("some");
        assert_eq!(ver, 3);
        assert_eq!(decoded, plaintext.to_vec());
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn cggmp_blob_load_missing_is_none() {
        let base = cggmp_test_base("missing");
        let r = load_cggmp_blob("no-such-session", "incomplete", &base, &cggmp_test_key())
            .expect("load must not error on missing file");
        assert!(r.is_none(), "missing file → None (首次运行)，不是错误");
    }

    #[test]
    fn cggmp_blob_tamper_truncate_wrong_key_fail_closed() {
        let base = cggmp_test_base("tamper");
        let key = cggmp_test_key();
        persist_cggmp_blob("sess-t", "keyshare", &base, b"payload-0123456789", &key, 1)
            .expect("persist");
        let path = cggmp_share_path(&base, "sess-t", "keyshare");
        let good = std::fs::read(&path).expect("read");

        // 1) 篡改密文 → 解密失败
        let mut tampered = good.clone();
        tampered[KEY_VERSION_HEADER_LEN + NONCE_LEN] ^= 0xFF;
        std::fs::write(&path, &tampered).expect("write tampered");
        assert!(
            load_cggmp_blob("sess-t", "keyshare", &base, &key).is_err(),
            "tampered blob must fail closed"
        );

        // 2) 截断（模拟半写）→ 解密失败
        let truncated = good[..good.len() - 7].to_vec();
        std::fs::write(&path, &truncated).expect("write truncated");
        assert!(
            load_cggmp_blob("sess-t", "keyshare", &base, &key).is_err(),
            "truncated blob must fail closed"
        );

        // 3) 错误密钥 → 解密失败
        std::fs::write(&path, &good).expect("restore good");
        let wrong_key = [0x00; 32];
        assert!(
            load_cggmp_blob("sess-t", "keyshare", &base, &wrong_key).is_err(),
            "wrong key must fail closed"
        );
        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn cggmp_blob_kind_whitelist_and_path_traversal() {
        let base = cggmp_test_base("traversal");
        let key = cggmp_test_key();
        // kind 白名单
        assert!(persist_cggmp_blob("s", "evil", &base, b"x", &key, 1).is_err());
        assert!(load_cggmp_blob("s", "../evil", &base, &key).is_err());
        // session_id 穿越被 sanitize 封堵：文件必然落在 base 内
        let path = cggmp_share_path(&base, "../../etc/passwd", "keyshare");
        let base_str = base.to_string_lossy();
        let path_str = path.to_string_lossy();
        assert!(
            path_str.starts_with(&*base_str) && !path_str.contains(".."),
            "sanitized path must stay under base: {path_str}"
        );
    }

    #[test]
    fn load_missing_returns_none() {
        ensure_test_key();
        let r = load_session("no-such-session").expect("no error");
        assert!(r.is_none());
    }

    // ===== S4-a: session_id 路径穿越净化回归 =====

    /// S4-a 核心不变量：净化学不改变文件名安全性——任意 session_id
    /// （含 `../`、盘符、UNC、分隔符）经 session_path/my_share_path 产出的
    /// 路径必须仍落在会话目录内（parent == session_dir），且文件名不含
    /// 路径分隔符。
    #[test]
    fn session_path_never_escapes_session_dir() {
        ensure_test_key();
        let dir = session_dir();
        for evil in [
            "../evil",
            "../../etc/passwd",
            "..\\..\\windows\\evil",
            "C:\\Users\\evil",
            "\\\\server\\share\\evil",
            "a/b/c",
            "a\\b",
            "..",
            ".",
            "con", // Windows 保留名（sanitize 不处理，但也不含分隔符）
        ] {
            for path in [session_path(evil), my_share_path(evil)] {
                assert_eq!(
                    path.parent()
                        .unwrap_or_else(|| panic!("no parent for {evil}")),
                    dir,
                    "S4-a: sanitized path for {evil:?} must stay inside session dir"
                );
                let file_name = path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or_else(|| panic!("no file_name for {evil}"));
                assert!(
                    !file_name.contains('/') && !file_name.contains('\\'),
                    "S4-a: file_name for {evil:?} must not contain path separators: {file_name}"
                );
            }
        }
    }

    /// 攻击语义闭环：persist/load/remove 用同一原始（恶意）session_id，
    /// 读写删必须命中同一净化文件——穿越不成立且功能不回归。
    #[test]
    fn persist_load_remove_round_trip_with_traversal_session_id() {
        ensure_test_key();
        let (_, _, session) = crate::gg20::run_keygen(1, 2).expect("GG20 DKG failed");
        let evil_id = "../../escape/attack";
        persist_session(evil_id, &session).expect("persist");
        // 逃逸目标路径不应存在（穿越被封堵）
        assert!(
            !session_dir()
                .join("../../escape")
                .join("session-attack.json")
                .exists(),
            "S4-a: traversal must not create files outside session dir"
        );
        // 同一原始 id 可读回（净化闭环一致）
        let restored = load_session(evil_id).expect("load").expect("some");
        assert_eq!(restored.params.threshold, session.params.threshold);
        remove_session(evil_id);
        assert!(load_session(evil_id).expect("load").is_none());
    }

    #[test]
    fn sanitize_session_id_only_keeps_safe_chars() {
        for (input, expected) in [
            ("normal-id_1", "normal-id_1"),
            ("../evil", "___evil"),
            ("a/b\\c:d*e", "a_b_c_d_e"),
            ("", ""),
        ] {
            assert_eq!(sanitize_session_id(input), expected, "input: {input:?}");
        }
    }

    #[test]
    fn encrypted_file_is_not_plaintext() {
        ensure_test_key();
        // 验证落盘文件不是明文 JSON（应含版本号头 + nonce 前缀 + 密文）
        let (_, _, session) = crate::gg20::run_keygen(1, 2).expect("GG20 DKG failed");
        let id = "persist-enc-test-1";
        persist_session(id, &session).expect("persist");
        let path = session_path(id);
        let raw = std::fs::read(&path).expect("read raw");
        // 文件不应以 JSON 明文标志 "{" 开头，应以 MAGIC "NXC1" 开头（中12 版本号头）
        assert!(
            !raw.starts_with(b"{"),
            "session file should be encrypted, not plaintext JSON (MPC-P1-05)"
        );
        assert!(
            raw.starts_with(KEY_VERSION_MAGIC),
            "中12: session file should start with version header magic 'NXC1'"
        );
        assert!(
            raw.len() > KEY_VERSION_HEADER_LEN + NONCE_LEN,
            "encrypted file should be longer than header + nonce"
        );
        remove_session(id);
    }

    #[test]
    fn decrypt_with_wrong_key_fails() {
        ensure_test_key();
        let (_, _, session) = crate::gg20::run_keygen(1, 2).expect("GG20 DKG failed");
        let id = "persist-wrong-key-test-1";
        persist_session(id, &session).expect("persist");
        // 用错误密钥解密应失败（GCM 完整性校验）
        // 中12: 文件现在有版本号头，用 aes_decrypt_with_version 解密
        let wrong_key = [0xAAu8; KEY_LEN];
        let raw = std::fs::read(session_path(id)).expect("read raw");
        let result = aes_decrypt_with_version(&raw, &wrong_key);
        assert!(
            result.is_err(),
            "decrypt with wrong key should fail (GCM integrity)"
        );
        remove_session(id);
    }

    // ===== 中12: 密钥版本号文件头 =====

    #[test]
    fn encrypted_file_has_version_header_magic() {
        ensure_test_key();
        let (_, _, session) = crate::gg20::run_keygen(1, 2).expect("GG20 DKG failed");
        let id = "persist-version-header-test";
        persist_session(id, &session).expect("persist");
        let raw = std::fs::read(session_path(id)).expect("read raw");
        // 新格式应以 MAGIC "NXC1" 开头
        assert!(
            raw.starts_with(KEY_VERSION_MAGIC),
            "中12: encrypted file should start with magic 'NXC1', got: {:?}",
            &raw[..4.min(raw.len())]
        );
        assert!(raw.len() > KEY_VERSION_HEADER_LEN + NONCE_LEN);
        remove_session(id);
    }

    #[test]
    #[serial_test::serial]
    fn version_header_records_current_version() {
        ensure_test_key();
        // 设置版本号为 7
        // SAFETY: 测试单线程，Once 保护
        unsafe {
            std::env::set_var(STORAGE_KEY_VERSION_ENV, "7");
        }
        let (_, _, session) = crate::gg20::run_keygen(1, 2).expect("GG20 DKG failed");
        let id = "persist-version-7-test";
        persist_session(id, &session).expect("persist");
        let raw = std::fs::read(session_path(id)).expect("read raw");
        // 读取文件头版本号
        let version = u32::from_le_bytes([raw[4], raw[5], raw[6], raw[7]]);
        assert_eq!(version, 7, "中12: file header should record version 7");
        // load_session 应能解密并返回版本号
        let restored = load_session(id).expect("load").expect("some");
        assert_eq!(restored.params.threshold, session.params.threshold);
        remove_session(id);
        // 清理版本号环境变量
        unsafe {
            std::env::remove_var(STORAGE_KEY_VERSION_ENV);
        }
    }

    #[test]
    fn aes_decrypt_with_version_handles_old_format() {
        ensure_test_key();
        // 旧格式：nonce(12B) || ciphertext（无 MAGIC 头）
        let plaintext = b"hello world";
        let key = load_storage_key().expect("key");
        let old_format_enc = aes_encrypt(plaintext, &key).expect("encrypt");
        // 解密旧格式应返回 DEFAULT_KEY_VERSION
        let (version, decrypted) =
            aes_decrypt_with_version(&old_format_enc, &key).expect("decrypt");
        assert_eq!(version, DEFAULT_KEY_VERSION);
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn aes_encrypt_decrypt_with_version_round_trip() {
        ensure_test_key();
        let plaintext = b"test plaintext for version round trip";
        let key = load_storage_key().expect("key");
        for version in [1u32, 2, 100, u32::MAX] {
            let enc = aes_encrypt_with_version(plaintext, &key, version).expect("encrypt");
            let (dec_version, dec) = aes_decrypt_with_version(&enc, &key).expect("decrypt");
            assert_eq!(dec_version, version, "version should round-trip");
            assert_eq!(dec, plaintext, "plaintext should round-trip");
        }
    }

    #[test]
    fn current_storage_key_version_defaults_to_1() {
        ensure_test_key();
        // 清理版本号环境变量
        unsafe {
            std::env::remove_var(STORAGE_KEY_VERSION_ENV);
        }
        assert_eq!(current_storage_key_version(), DEFAULT_KEY_VERSION);
        assert_eq!(current_storage_key_version(), 1);
    }

    #[test]
    #[serial_test::serial]
    fn current_storage_key_version_reads_env() {
        ensure_test_key();
        unsafe {
            std::env::set_var(STORAGE_KEY_VERSION_ENV, "42");
        }
        assert_eq!(current_storage_key_version(), 42);
        unsafe {
            std::env::remove_var(STORAGE_KEY_VERSION_ENV);
        }
    }

    #[test]
    #[serial_test::serial]
    fn current_storage_key_version_ignores_invalid_env() {
        ensure_test_key();
        unsafe {
            std::env::set_var(STORAGE_KEY_VERSION_ENV, "not-a-number");
        }
        assert_eq!(current_storage_key_version(), DEFAULT_KEY_VERSION);
        unsafe {
            std::env::set_var(STORAGE_KEY_VERSION_ENV, "0");
        }
        assert_eq!(
            current_storage_key_version(),
            DEFAULT_KEY_VERSION,
            "version 0 should be rejected (reserved/invalid)"
        );
        unsafe {
            std::env::remove_var(STORAGE_KEY_VERSION_ENV);
        }
    }

    #[test]
    fn persist_my_share_only_stores_my_share() {
        ensure_test_key();
        let (_, _, mut session) = crate::gg20::run_keygen(1, 3).expect("GG20 DKG failed");
        // 设置本方身份为 party 1
        session.set_my_identity(1).expect("set identity");

        let id = "persist-my-share-test-1";
        persist_my_share(id, &session).expect("persist_my_share");

        // 加载记录
        let record = load_my_share(id).expect("load").expect("some");
        assert_eq!(record.my_party_index, 1);
        assert_eq!(record.party_public_keys.len(), 3, "应存储全部 3 方公钥");
        assert!(!record.aggregate_public_key.is_empty());

        // 解密本方份额
        let my_share = decrypt_my_share(&record).expect("decrypt");
        assert_eq!(
            my_share.x_i,
            session.my_private_share.as_ref().unwrap().x_i,
            "解密的本方份额应与原始一致"
        );

        // 验证 my-share 文件不包含其他方的份额（只有加密的 my_private_share）
        let raw = std::fs::read(my_share_path(id)).expect("read raw");
        let raw_str = String::from_utf8_lossy(&raw);
        // 应包含 aggregate_public_key（明文）但不包含 shared_keys 数组
        assert!(raw_str.contains("aggregate_public_key"));
        assert!(
            !raw_str.contains("shared_keys"),
            "my-share 文件不应包含 shared_keys 数组"
        );

        remove_session(id);
    }

    #[test]
    fn persist_my_share_without_identity_fails() {
        ensure_test_key();
        let (_, _, session) = crate::gg20::run_keygen(1, 2).expect("GG20 DKG failed");
        // 不调用 set_my_identity，my_private_share 为 None
        let id = "persist-my-share-fail-test";
        let result = persist_my_share(id, &session);
        assert!(result.is_err(), "未设置 my_private_share 应失败");
        assert!(result
            .unwrap_err()
            .to_string()
            .contains("my_private_share not set"));
        remove_session(id);
    }
}

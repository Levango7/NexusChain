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

use crate::gg20::DkgSession;
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use eyre::eyre;
use rand::rngs::OsRng;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

/// 会话目录环境变量。
const SESSION_DIR_ENV: &str = "MPC_ENGINE_SESSION_DIR";

/// AES-256-GCM 密钥环境变量名（MPC-P1-05）。
const STORAGE_KEY_ENV: &str = "MPC_STORAGE_KEY";

/// GCM nonce 长度（字节）。
const NONCE_LEN: usize = 12;

/// AES-256 密钥长度（字节）。
const KEY_LEN: usize = 32;

/// 获取会话目录（可配置，默认 ./mpc-sessions）。
pub fn session_dir() -> PathBuf {
    std::env::var(SESSION_DIR_ENV)
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("./mpc-sessions"))
}

fn session_path(session_id: &str) -> PathBuf {
    session_dir().join(format!("session-{}.json", session_id))
}

/// MPC-P2-F5: 本方份额持久化文件路径（与全量会话文件分离）。
fn my_share_path(session_id: &str) -> PathBuf {
    session_dir().join(format!("my-share-{}.json", session_id))
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
            STORAGE_KEY_ENV, STORAGE_KEY_ENV
        )
    })?;
    let key_bytes = hex::decode(&key_hex)
        .map_err(|e| eyre!("{} hex decode failed: {e}", STORAGE_KEY_ENV))?;
    if key_bytes.len() != KEY_LEN {
        return Err(eyre!(
            "{} must be {} bytes ({} hex chars), got {} bytes",
            STORAGE_KEY_ENV, KEY_LEN, KEY_LEN * 2, key_bytes.len()
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
fn aes_encrypt(plaintext: &[u8], key: &[u8; KEY_LEN]) -> eyre::Result<Vec<u8>> {
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
fn aes_decrypt(data: &[u8], key: &[u8; KEY_LEN]) -> eyre::Result<Vec<u8>> {
    if data.len() < NONCE_LEN {
        return Err(eyre!(
            "encrypted data too short ({} < {}): corrupted or not encrypted with MPC-P1-05 format",
            data.len(), NONCE_LEN
        ));
    }
    let (nonce_bytes, ciphertext) = data.split_at(NONCE_LEN);
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    cipher
        .decrypt(Nonce::from_slice(nonce_bytes), ciphertext)
        .map_err(|e| eyre!("AES-256-GCM decrypt failed (wrong key or tampered?): {e}"))
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
    let json = serde_json::to_vec_pretty(session)
        .map_err(|e| eyre!("session serialize failed: {e}"))?;
    // MPC-P1-05: AES-256-GCM 加密后落盘（防明文份额泄露）
    let key = load_storage_key()?;
    let encrypted = aes_encrypt(&json, &key)?;
    let path = session_path(session_id);
    fs::write(&path, encrypted)
        .map_err(|e| eyre!("cannot write session {}: {e}", path.display()))?;
    tracing::info!(
        session_id = %session_id,
        path = %path.display(),
        encrypted_bytes = json.len(),
        "dkg session persisted (AES-256-GCM encrypted, MPC-P1-05)"
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
    let bytes = fs::read(&path)
        .map_err(|e| eyre!("cannot read session {}: {e}", path.display()))?;
    // MPC-P1-05: AES-256-GCM 解密
    let key = load_storage_key()?;
    let plaintext = aes_decrypt(&bytes, &key)?;
    let session: DkgSession = serde_json::from_slice(&plaintext)
        .map_err(|e| eyre!("session deserialize failed: {e}"))?;
    tracing::info!(session_id = %session_id, "dkg session restored from disk (decrypted, MPC-P1-05)");
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
#[derive(Clone, Serialize, Deserialize)]
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
    let key = load_storage_key()?;
    let share_json = serde_json::to_vec_pretty(my_share)
        .map_err(|e| eyre!("my_private_share serialize failed: {e}"))?;
    let encrypted_share = aes_encrypt(&share_json, &key)?;

    // 公钥材料明文（hex 编码）
    let aggregate_public_key = crate::gg20::hex_point(&session.y_sum);
    let party_public_keys: Vec<String> = session
        .pk_vec
        .iter()
        .map(crate::gg20::hex_point)
        .collect();

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
    fs::write(&path, json)
        .map_err(|e| eyre!("cannot write my-share {}: {e}", path.display()))?;

    tracing::info!(
        session_id = %session_id,
        path = %path.display(),
        my_party_index = record.my_party_index,
        party_public_keys_count = record.party_public_keys.len(),
        "MPC-P2-F5: my private share persisted (AES-256-GCM encrypted), \
         public keys stored in plaintext (for verification)"
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
    let bytes = fs::read(&path)
        .map_err(|e| eyre!("cannot read my-share {}: {e}", path.display()))?;
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
pub fn decrypt_my_share(
    record: &MyShareRecord,
) -> eyre::Result<crate::gg20::SharedKeysSerde> {
    let key = load_storage_key()?;
    let plaintext = aes_decrypt(&record.encrypted_private_share, &key)?;
    let share: crate::gg20::SharedKeysSerde = serde_json::from_slice(&plaintext)
        .map_err(|e| eyre!("my_private_share deserialize failed: {e}"))?;
    Ok(share)
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
        let (_, _, session) = crate::gg20::run_keygen(1, 2)
            .expect("GG20 DKG failed");
        let id = "persist-test-1";
        persist_session(id, &session).expect("persist");
        let restored = load_session(id).expect("load").expect("some");
        assert_eq!(restored.params.threshold, session.params.threshold);
        assert_eq!(restored.params.share_count, session.params.share_count);
        assert_eq!(restored.y_sum, session.y_sum, "聚合公钥应一致");
        remove_session(id);
    }

    #[test]
    fn load_missing_returns_none() {
        ensure_test_key();
        let r = load_session("no-such-session").expect("no error");
        assert!(r.is_none());
    }

    #[test]
    fn encrypted_file_is_not_plaintext() {
        ensure_test_key();
        // 验证落盘文件不是明文 JSON（应含 nonce 前缀 + 密文）
        let (_, _, session) = crate::gg20::run_keygen(1, 2)
            .expect("GG20 DKG failed");
        let id = "persist-enc-test-1";
        persist_session(id, &session).expect("persist");
        let path = session_path(id);
        let raw = std::fs::read(&path).expect("read raw");
        // 文件不应以 JSON 明文标志 "{" 开头，应以 nonce（12 字节随机）开头
        assert!(
            !raw.starts_with(b"{"),
            "session file should be encrypted, not plaintext JSON (MPC-P1-05)"
        );
        assert!(raw.len() > NONCE_LEN, "encrypted file should be longer than nonce");
        remove_session(id);
    }

    #[test]
    fn decrypt_with_wrong_key_fails() {
        ensure_test_key();
        let (_, _, session) = crate::gg20::run_keygen(1, 2)
            .expect("GG20 DKG failed");
        let id = "persist-wrong-key-test-1";
        persist_session(id, &session).expect("persist");
        // 用错误密钥解密应失败（GCM 完整性校验）
        let wrong_key = [0xAAu8; KEY_LEN];
        let raw = std::fs::read(session_path(id)).expect("read raw");
        let result = aes_decrypt(&raw, &wrong_key);
        assert!(result.is_err(), "decrypt with wrong key should fail (GCM integrity)");
        remove_session(id);
    }

    #[test]
    fn persist_my_share_only_stores_my_share() {
        ensure_test_key();
        let (_, _, mut session) = crate::gg20::run_keygen(1, 3)
            .expect("GG20 DKG failed");
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
        assert!(!raw_str.contains("shared_keys"), "my-share 文件不应包含 shared_keys 数组");

        remove_session(id);
    }

    #[test]
    fn persist_my_share_without_identity_fails() {
        ensure_test_key();
        let (_, _, session) = crate::gg20::run_keygen(1, 2)
            .expect("GG20 DKG failed");
        // 不调用 set_my_identity，my_private_share 为 None
        let id = "persist-my-share-fail-test";
        let result = persist_my_share(id, &session);
        assert!(result.is_err(), "未设置 my_private_share 应失败");
        assert!(result.unwrap_err().to_string().contains("my_private_share not set"));
        remove_session(id);
    }
}

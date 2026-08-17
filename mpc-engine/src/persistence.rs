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

use crate::gg20::DkgSession;
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use eyre::eyre;
use rand::rngs::OsRng;
use rand::RngCore;
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
            std::env::set_var(STORAGE_KEY_ENV, key_hex);
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
}

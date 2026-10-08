//! MPC 会话持久化：CGGMP21 协议产物（keygen 中间态 / 完整 KeyShare）的
//! 加密落盘与恢复。
//!
//! **MPC-P1-05 + 中12 加密信封**：落盘前用 **AES-256-GCM** 认证加密，密钥从
//! 环境变量 `MPC_STORAGE_KEY` 读取（hex 编码的 32 字节）。文件格式：
//! `MAGIC("NXC1") || version(4B LE) || nonce(12B) || ciphertext`，GCM 自带
//! 完整性校验防篡改；密钥版本号支持轮换（旧格式无头视为版本 1）。
//! 引擎侧隔离进程持有密钥材料不跨进程传输（方案 A"份额只在参与者进程"语义）。
//!
//! **GG20 退役（PLAN-001-R2）**：GG20 时代的三类落盘产物——`persist_session`
//! 全量会话快照、`persist_my_share` 本方份额隔离记录（`MyShareRecord`）——
//! 随 GG20 路径一并删除。**存量旧文件不再被任何代码路径读取**（CGGMP21 路径
//! 重新生成份额），不做迁移；如需清理可手工删除会话目录下的
//! `session-*.json` / `my-share-*.json`。
//!
//! **S4-a 修复（session_id 路径穿越净化）**：RPC 原始 session_id 在拼接落盘
//! 文件名前一律经 `sanitize_session_id` 净化（仅保留 [A-Za-z0-9-_]），
//! 产物被约束为会话目录内的单文件名——封堵 `../` 逃逸、Windows 盘符冒号、
//! UNC 前缀与嵌套分隔符；persist/load 两入口共用同一净化函数，读写闭环一致。

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use eyre::eyre;
use rand_core::{OsRng, RngCore};
use std::fs;
use std::path::{Path, PathBuf};

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
/// `pub(crate)`：cggmp_state 落盘路径与本模块测试断言魔数前缀。
pub(crate) const KEY_VERSION_MAGIC: &[u8; 4] = b"NXC1";

/// 中12: 密钥版本号文件头长度（MAGIC 4B + version 4B LE）。
const KEY_VERSION_HEADER_LEN: usize = 8;

/// 中12: 默认密钥版本号（旧文件无版本头时视为此版本）。
const DEFAULT_KEY_VERSION: u32 = 1;

/// S4-a: session_id 文件名安全化（仅保留 [A-Za-z0-9-_]，其余字符替换为 '_'）。
///
/// **修复背景**：GG20 可信协调器路径曾把 RPC 原始 `session_id` 直接拼入
/// 落盘文件名——含 `../` 的 session_id 可穿越会话目录逃逸写任意路径。
/// 净化后产物只含安全字符集，`base_dir.join(sanitized)` 必然落在会话目录内
/// 的单文件名（无 `/`、`\`、盘符冒号、UNC 前缀），穿越被结构性封堵。
/// CGGMP21 落盘路径（`cggmp_share_path`）复用本函数。
///
/// `pub(crate)`：cggmp 落盘路径与本模块测试复用。
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

/// 低9: 设置文件权限为 0600（仅所有者可读写），Unix 特有。
///
/// Windows 上此函数为空操作（`#[cfg(not(unix))]`），因 Unix 权限模型不适用。
/// Windows 上文件权限通过 ACL 管理，应由部署环境（如 NTFS ACL）单独配置。
///
/// # 安全
/// 0600 = rw-------（所有者读写，组与其他无任何权限）。
/// 防止其他用户/进程读取加密文件（虽然文件已加密，但权限收紧是纵深防御）。
///
/// `pub(crate)`：CGGMP21 份额落盘路径（`persist_cggmp_blob`）复用此函数。
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
/// `pub(crate)`：CGGMP21 份额落盘路径（persist_cggmp_blob）复用此原语。
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
/// `pub(crate)`：CGGMP21 份额落盘路径（persist_cggmp_blob）复用此原语。
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
/// `pub(crate)`：CGGMP21 份额读取路径（load_cggmp_blob）复用此原语。
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

// =========================================================================
// CGGMP21 份额持久化（PLAN-cggmp-keyshare-persistence，K 批前置）
// =========================================================================
// NXC1 信封：`MAGIC("NXC1") || version(4B LE) || nonce(12B) || GCM ciphertext`。
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
    // 低9: 设置 0600 权限（仅所有者可读写，Unix 特有，Windows 空操作）——
    // GG20 会话文件退役后，此纵深防御沿用至 CGGMP21 份额落盘路径。
    set_secure_permissions(&path);
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

    // ===== S4-a: session_id 路径穿越净化回归 =====

    /// S4-a 核心不变量：净化学不改变文件名安全性——任意 session_id
    /// （含 `../`、盘符、UNC、分隔符）经 cggmp_share_path 产出的
    /// 路径必须仍落在 base_dir 内，且文件名不含路径分隔符。
    #[test]
    fn cggmp_share_path_never_escapes_base_dir() {
        let base = std::path::Path::new("/tmp/s4a-base");
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
            for kind in ["keyshare", "incomplete"] {
                let path = cggmp_share_path(base, evil, kind);
                assert!(
                    path.starts_with(base),
                    "S4-a: sanitized path for {evil:?} must stay under base: {}",
                    path.display()
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

    // ===== 中12: 密钥版本号文件头 =====

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
}

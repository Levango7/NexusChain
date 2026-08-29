//! MPC-P2-F5 各方独立进程配置。
//!
//! 分布式安全模型下，每个 mpc-engine 进程代表一个 MPC 参与方（party），
//! 持有自己的 `PartyConfig`：本方索引、监听地址、对端节点列表、
//! 本地存储加密密钥、mTLS 证书与 CA。
//!
//! 配置文件格式为 JSON（serde 反序列化），路径通过 `--config` 命令行参数
//! 或 `MPC_CONFIG_PATH` 环境变量指定。示例：
//!
//! ```json
//! {
//!   "party_index": 0,
//!   "party_id": "party-0",
//!   "listen_addr": "0.0.0.0:50051",
//!   "peers": [
//!     { "party_index": 1, "party_id": "party-1", "endpoint": "https://party-1:50051" }
//!   ],
//!   "storage_key": "4242424242424242424242424242424242424242424242424242424242424242",
//!   "tls_cert": "/etc/mpc/tls/party-0.crt",
//!   "tls_key": "/etc/mpc/tls/party-0.key",
//!   "tls_ca": "/etc/mpc/tls/ca.crt"
//! }
//! ```

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
// zeroize：密钥材料安全擦除。PartyConfig.storage_key（AES-256-GCM 密钥 hex）
// 与 storage_keys（多密钥映射）派生 Zeroize，在配置离开作用域前擦除密钥材料内存。
use zeroize::Zeroize;

/// 对端参与方配置。
///
/// **密钥材料安全擦除**：派生 `Zeroize`。字段（`usize`、`String`）均实现
/// `Zeroize`，派生后调用 `zeroize()` 将 `party_id`、`endpoint` 等内存清零。
#[derive(Clone, Debug, Serialize, Deserialize, Zeroize)]
pub struct PeerConfig {
    /// 对端方索引（0..n）。
    pub party_index: usize,
    /// 对端方标识（人类可读，用于 session_id 身份绑定）。
    pub party_id: String,
    /// 对端 gRPC 端点（含 scheme，如 `https://party-1:50051`）。
    pub endpoint: String,
}

/// 本方参与方配置（MPC-P2-F5 分布式安全模型）。
///
/// 每个 mpc-engine 进程持有一份 `PartyConfig`，标识本方身份、
/// 监听地址、对端节点、本地存储加密密钥与 mTLS 证书。
///
/// # 密钥轮换（中12）
/// `storage_key_version` 标识当前加密所用密钥版本号。新文件用当前版本加密，
/// 旧文件由 persistence 模块根据文件头中记录的版本号选择对应密钥解密。
/// 多密钥映射 `storage_keys` 支持"旧密钥解密旧文件、新密钥加密新文件"的过渡期
/// （TODO: 完整多密钥支持待实现，当前仅单密钥 + 版本号记录）。
///
/// # 密钥来源（低10）
/// `storage_key_source` 控制 `storage_key` 的读取来源：
///   * `"plain"`（默认）：从配置文件 `storage_key` 字段读取（向后兼容）
///   * `"env"`：从环境变量 `NEXUS_MPC_STORAGE_KEY` 读取
///   * `"kms"`：调用 KMS API 解密（TODO，依赖部署环境）
///
/// **密钥材料安全擦除**：派生 `Zeroize`。所有字段（`String`、`Vec<PeerConfig>`、
/// `HashMap<u32, String>`、`usize`、`u32`）均实现 `Zeroize`，派生后调用
/// `zeroize()` 将 `storage_key`（AES-256-GCM 密钥 hex）、`storage_keys`
///（多密钥映射）、`tls_key`（mTLS 私钥路径）等敏感材料内存清零。
/// 未派生 `ZeroizeOnDrop` 以避免改变现有 Drop 语义，调用方需显式调用 `zeroize()`。
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PartyConfig {
    /// 本方索引（0..n），对应 DKG/Sign RPC 的 `party_index`。
    pub party_index: usize,
    /// 本方标识（人类可读，用于 session_id 身份绑定与安全日志）。
    pub party_id: String,
    /// 本方 gRPC 监听地址（如 `0.0.0.0:50051`）。
    pub listen_addr: String,
    /// 对端参与方列表（不含本方）。
    #[serde(default)]
    pub peers: Vec<PeerConfig>,
    /// 本地存储加密密钥（hex 编码的 32 字节 AES-256-GCM 密钥）。
    /// 对应环境变量 `MPC_STORAGE_KEY`；配置文件优先于环境变量。
    ///
    /// 当 `storage_key_source = "env"` 时此字段可为空（从环境变量读取）。
    /// 当 `storage_key_source = "kms"` 时此字段应为 KMS 加密的密文（TODO）。
    #[serde(default)]
    pub storage_key: String,
    /// 中12: 当前加密所用 storage_key 的版本号。
    ///
    /// 新文件用此版本号加密；解密时根据文件头中记录的版本号选择对应密钥。
    /// 默认为 1（向后兼容：未配置时视为版本 1）。
    /// 轮换密钥时：1) 新增 `storage_keys` 中新版本→新密钥映射；2) 递增此版本号；
    /// 3) 重启后新文件用新版本加密，旧文件仍可用旧版本密钥解密。
    #[serde(default = "default_storage_key_version")]
    pub storage_key_version: u32,
    /// 中12: 多密钥映射（版本号 → hex 编码密钥），支持密钥轮换过渡期。
    ///
    /// 解密时根据文件头版本号从此映射查找对应密钥；若映射中无该版本，
    /// 回退到 `storage_key`（单密钥模式，向后兼容）。
    /// 默认为空 HashMap（单密钥模式）。
    ///
    /// TODO: 完整多密钥支持待实现——当前 persistence 模块仅记录版本号到文件头，
    /// 解密时仍使用 `storage_key`（单密钥）。完整实现需 persistence 模块根据版本号
    /// 从此映射选择密钥，并支持旧文件用旧密钥解密、新文件用新密钥加密。
    #[serde(default)]
    pub storage_keys: HashMap<u32, String>,
    /// 低10: storage_key 读取来源。
    ///
    ///   * `"plain"`（默认）：从配置文件 `storage_key` 字段读取（向后兼容）
    ///   * `"env"`：从环境变量 `NEXUS_MPC_STORAGE_KEY` 读取
    ///   * `"kms"`：调用 KMS API 解密（TODO，依赖部署环境）
    #[serde(default = "default_storage_key_source")]
    pub storage_key_source: String,
    /// 本方 mTLS 证书路径（PEM 格式）。
    pub tls_cert: String,
    /// 本方 mTLS 私钥路径（PEM 格式）。
    pub tls_key: String,
    /// mTLS CA 证书路径（PEM 格式，用于验证对端证书）。
    pub tls_ca: String,
}

/// `storage_key_version` 的 serde 默认值（1）。
fn default_storage_key_version() -> u32 {
    1
}

/// `storage_key_source` 的 serde 默认值（"plain"）。
fn default_storage_key_source() -> String {
    "plain".to_string()
}

/// 手动实现 `Zeroize`（A1-P1b）：zeroize 1.9 的 `derive(Zeroize)` 对含
/// `HashMap` 的结构体派生失败（trait bound 不满足），改为手动擦除——
/// `String`/`Vec` 字段直接 `zeroize()`，`HashMap<u32, String>` 逐 value 擦除，
/// 与 `PeerConfig` 的派生实现保持一致的安全意图。
impl Zeroize for PartyConfig {
    fn zeroize(&mut self) {
        self.party_id.zeroize();
        self.listen_addr.zeroize();
        self.peers.zeroize();
        self.storage_key.zeroize();
        for v in self.storage_keys.values_mut() {
            v.zeroize();
        }
        self.storage_key_source.zeroize();
        self.tls_cert.zeroize();
        self.tls_key.zeroize();
        self.tls_ca.zeroize();
    }
}

/// 低10: 从环境变量或 KMS 读取 storage_key 的环境变量名。
///
/// 注意：此环境变量名与 persistence 模块的 `MPC_STORAGE_KEY` 不同——
/// `NEXUS_MPC_STORAGE_KEY` 是用户配置的密钥来源（低10），
/// `MPC_STORAGE_KEY` 是 persistence 模块内部使用的环境变量
/// （由 `apply_storage_key_to_env` 从配置同步而来）。
pub const NEXUS_STORAGE_KEY_ENV: &str = "NEXUS_MPC_STORAGE_KEY";

/// 配置文件路径环境变量名。
pub const CONFIG_PATH_ENV: &str = "MPC_CONFIG_PATH";

/// 从 JSON 文件加载 `PartyConfig`。
///
/// 路径优先级：显式参数 > `MPC_CONFIG_PATH` 环境变量。
pub fn load_config(path: Option<&str>) -> eyre::Result<PartyConfig> {
    let config_path = match path {
        Some(p) if !p.is_empty() => p.to_string(),
        _ => std::env::var(CONFIG_PATH_ENV).map_err(|_| {
            eyre::eyre!(
                "MPC-P2-F5: config path not provided. \
                 Pass --config <path> or set {} env var (distributed security model requires \
                 per-party config: party_index, listen_addr, peers, storage_key, mTLS certs)",
                CONFIG_PATH_ENV
            )
        })?,
    };

    let content = std::fs::read_to_string(&config_path).map_err(|e| {
        eyre::eyre!(
            "MPC-P2-F5: failed to read config file '{}': {e}",
            config_path
        )
    })?;

    let config: PartyConfig = serde_json::from_str(&content).map_err(|e| {
        eyre::eyre!(
            "MPC-P2-F5: failed to parse config file '{}' as JSON: {e}",
            config_path
        )
    })?;

    config.validate()?;
    tracing::info!(
        config_path = %config_path,
        party_index = config.party_index,
        party_id = %config.party_id,
        listen_addr = %config.listen_addr,
        peers_count = config.peers.len(),
        has_tls = !config.tls_cert.is_empty(),
        "MPC-P2-F5: party config loaded (distributed security model)"
    );
    Ok(config)
}

impl PartyConfig {
    /// 校验配置完整性。
    pub fn validate(&self) -> eyre::Result<()> {
        if self.party_id.is_empty() {
            return Err(eyre::eyre!(
                "MPC-P2-F5: party_id must be non-empty (distributed security model)"
            ));
        }
        if self.listen_addr.is_empty() {
            return Err(eyre::eyre!("MPC-P2-F5: listen_addr must be non-empty"));
        }
        // storage_key 校验：根据 storage_key_source 决定校验策略
        // 低10: 支持 "plain" / "env" / "kms" 三种来源
        match self.storage_key_source.as_str() {
            "plain" => {
                // plain 模式：storage_key 必须在配置文件中提供且为 64 hex 字符（32 字节）
                if self.storage_key.is_empty() {
                    return Err(eyre::eyre!(
                        "MPC-P2-F5: storage_key must be non-empty (64-char hex encoding 32 bytes \
                         for AES-256-GCM) when storage_key_source='plain'"
                    ));
                }
                let key_bytes = hex::decode(&self.storage_key)
                    .map_err(|e| eyre::eyre!("MPC-P2-F5: storage_key hex decode failed: {e}"))?;
                if key_bytes.len() != 32 {
                    return Err(eyre::eyre!(
                        "MPC-P2-F5: storage_key must be 32 bytes (64 hex chars), got {} bytes",
                        key_bytes.len()
                    ));
                }
            }
            "env" => {
                // env 模式：storage_key 从 NEXUS_MPC_STORAGE_KEY 读取，配置文件中可为空
                // 实际值在 resolve_storage_key 时校验
                tracing::info!(
                    env_var = %NEXUS_STORAGE_KEY_ENV,
                    "低10: storage_key_source='env' — storage_key will be read from environment variable"
                );
            }
            "kms" => {
                // kms 模式：TODO，KMS 解密未实现
                tracing::warn!(
                    "低10: storage_key_source='kms' — KMS decryption not yet implemented (TODO); \
                     resolve_storage_key will return error at runtime"
                );
            }
            other => {
                return Err(eyre::eyre!(
                    "MPC-P2-F5: invalid storage_key_source '{}', must be one of: \
                     'plain' (default), 'env', 'kms'",
                    other
                ));
            }
        }

        // 中12: storage_key_version 校验（必须 >= 1）
        if self.storage_key_version == 0 {
            return Err(eyre::eyre!(
                "中12: storage_key_version must be >= 1 (0 is reserved/invalid)"
            ));
        }
        // 中12: storage_keys 中每个密钥校验格式（若提供）
        for (ver, key_hex) in &self.storage_keys {
            if *ver == 0 {
                return Err(eyre::eyre!(
                    "中12: storage_keys contains version 0 (reserved/invalid)"
                ));
            }
            let key_bytes = hex::decode(key_hex)
                .map_err(|e| eyre::eyre!("中12: storage_keys[{}] hex decode failed: {e}", ver))?;
            if key_bytes.len() != 32 {
                return Err(eyre::eyre!(
                    "中12: storage_keys[{}] must be 32 bytes (64 hex chars), got {} bytes",
                    ver,
                    key_bytes.len()
                ));
            }
        }
        // 中12: 当前版本密钥必须可用（storage_keys 非空时当前版本必须在映射中，否则回退到 storage_key）
        if !self.storage_keys.is_empty()
            && !self.storage_keys.contains_key(&self.storage_key_version)
        {
            tracing::warn!(
                version = self.storage_key_version,
                "中12: storage_keys does not contain current storage_key_version — \
                 will fall back to storage_key field (single-key mode)"
            );
        }

        // mTLS 证书路径校验
        if self.tls_cert.is_empty() || self.tls_key.is_empty() || self.tls_ca.is_empty() {
            return Err(eyre::eyre!(
                "MPC-P2-F5: tls_cert, tls_key, tls_ca must all be non-empty \
                 (mTLS is mandatory in distributed security model)"
            ));
        }
        // 对端配置校验：不应包含本方
        for peer in &self.peers {
            if peer.party_index == self.party_index {
                return Err(eyre::eyre!(
                    "MPC-P2-F5: peers list contains self (party_index {}); \
                     peers should only include other parties",
                    self.party_index
                ));
            }
            if peer.party_id.is_empty() || peer.endpoint.is_empty() {
                return Err(eyre::eyre!(
                    "MPC-P2-F5: peer party_id and endpoint must be non-empty"
                ));
            }
        }
        Ok(())
    }

    /// 低10: 解析实际使用的 storage_key（根据 `storage_key_source`）。
    ///
    ///   * `"plain"`：返回 `self.storage_key`（配置文件值）
    ///   * `"env"`：从环境变量 `NEXUS_MPC_STORAGE_KEY` 读取
    ///   * `"kms"`：调用 KMS API 解密（TODO，当前返回错误）
    ///
    /// 返回 hex 编码的 64 字符密钥字符串。调用方负责 hex 解码与长度校验。
    ///
    /// 此方法不校验密钥格式（由 `validate` 或调用方负责），仅负责"从哪里取"。
    pub fn resolve_storage_key(&self) -> eyre::Result<String> {
        match self.storage_key_source.as_str() {
            "env" => std::env::var(NEXUS_STORAGE_KEY_ENV).map_err(|_| {
                eyre::eyre!(
                    "低10: storage_key_source='env' but environment variable {} not set \
                     — set it to a 64-char hex string encoding 32 bytes (AES-256-GCM key)",
                    NEXUS_STORAGE_KEY_ENV
                )
            }),
            "kms" => Err(eyre::eyre!(
                "低10: storage_key_source='kms' but KMS decryption not yet implemented (TODO); \
                 use 'plain' or 'env' source for now"
            )),
            // "plain" 或其他值（validate 已校验，此处默认 plain 行为）
            _ => {
                if self.storage_key.is_empty() {
                    return Err(eyre::eyre!(
                        "低10: storage_key_source='plain' but storage_key field is empty in config"
                    ));
                }
                Ok(self.storage_key.clone())
            }
        }
    }

    /// 中12: 根据版本号查找密钥（hex 编码）。
    ///
    /// 优先从 `storage_keys` 映射查找；若映射中无该版本或映射为空，
    /// 回退到 `resolve_storage_key`（单密钥模式，向后兼容）。
    ///
    /// 返回 hex 编码的 64 字符密钥字符串。
    pub fn resolve_storage_key_for_version(&self, version: u32) -> eyre::Result<String> {
        if let Some(key_hex) = self.storage_keys.get(&version) {
            return Ok(key_hex.clone());
        }
        // 回退：当前版本号 == 请求版本号时用 storage_key；否则错误
        if version == self.storage_key_version {
            return self.resolve_storage_key();
        }
        Err(eyre::eyre!(
            "中12: no storage_key for version {} (not in storage_keys map and != current version {})",
            version,
            self.storage_key_version
        ))
    }

    /// 中12: 当前密钥版本号。
    pub fn current_storage_key_version(&self) -> u32 {
        self.storage_key_version
    }

    /// 将 storage_key 设置到环境变量 `MPC_STORAGE_KEY`（供 persistence 模块读取）。
    ///
    /// 分布式模式下，storage_key 来自配置文件（或环境变量/KMS，低10）；此方法在启动时
    /// 将解析后的值同步到 `MPC_STORAGE_KEY` 环境变量，保持 persistence 模块的
    /// `load_storage_key` 兼容（persistence 模块仍从 `MPC_STORAGE_KEY` 读取）。
    ///
    /// # Safety
    /// 仅在单线程启动阶段调用（main 函数早期，未 spawn 其他线程）。
    /// `std::env::set_var` 在多线程环境下是 unsafe 的（可能引起其他线程的
    /// `std::env::var` 数据竞争），但启动阶段单线程调用是安全的。
    pub fn apply_storage_key_to_env(&self) -> eyre::Result<()> {
        let key = self.resolve_storage_key()?;
        // SAFETY: 仅在 main 启动阶段单线程调用，未 spawn 任何工作线程。
        // persistence 模块后续在多线程 gRPC 处理中只读 `std::env::var`（只读安全）。
        unsafe {
            std::env::set_var("MPC_STORAGE_KEY", &key);
            // 中12: 同步密钥版本号到环境变量，供 persistence 模块加密新文件时写入文件头
            std::env::set_var(
                "MPC_STORAGE_KEY_VERSION",
                self.storage_key_version.to_string(),
            );
        }
        tracing::info!(
            version = self.storage_key_version,
            source = %self.storage_key_source,
            "中12+低10: storage_key applied to MPC_STORAGE_KEY env var (version {}, source '{}')",
            self.storage_key_version,
            self.storage_key_source
        );
        Ok(())
    }

    /// 查找对端方配置。
    pub fn peer(&self, party_index: usize) -> Option<&PeerConfig> {
        self.peers.iter().find(|p| p.party_index == party_index)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_config_json() -> &'static str {
        r#"{
            "party_index": 0,
            "party_id": "party-0",
            "listen_addr": "0.0.0.0:50051",
            "peers": [
                {"party_index": 1, "party_id": "party-1", "endpoint": "https://party-1:50051"},
                {"party_index": 2, "party_id": "party-2", "endpoint": "https://party-2:50051"}
            ],
            "storage_key": "4242424242424242424242424242424242424242424242424242424242424242",
            "tls_cert": "/etc/mpc/tls/party-0.crt",
            "tls_key": "/etc/mpc/tls/party-0.key",
            "tls_ca": "/etc/mpc/tls/ca.crt"
        }"#
    }

    #[test]
    fn parse_and_validate_sample_config() {
        let config: PartyConfig = serde_json::from_str(sample_config_json()).expect("parse config");
        config.validate().expect("validate should pass");
        assert_eq!(config.party_index, 0);
        assert_eq!(config.peers.len(), 2);
        assert!(config.peer(1).is_some());
        assert!(config.peer(3).is_none());
    }

    #[test]
    fn reject_config_with_self_in_peers() {
        let json = r#"{
            "party_index": 0,
            "party_id": "party-0",
            "listen_addr": "0.0.0.0:50051",
            "peers": [
                {"party_index": 0, "party_id": "party-0", "endpoint": "https://party-0:50051"}
            ],
            "storage_key": "4242424242424242424242424242424242424242424242424242424242424242",
            "tls_cert": "/etc/mpc/tls/party-0.crt",
            "tls_key": "/etc/mpc/tls/party-0.key",
            "tls_ca": "/etc/mpc/tls/ca.crt"
        }"#;
        let config: PartyConfig = serde_json::from_str(json).expect("parse");
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("peers list contains self"));
    }

    #[test]
    fn reject_config_with_bad_storage_key_len() {
        let json = r#"{
            "party_index": 0,
            "party_id": "party-0",
            "listen_addr": "0.0.0.0:50051",
            "peers": [],
            "storage_key": "42",
            "tls_cert": "/etc/mpc/tls/party-0.crt",
            "tls_key": "/etc/mpc/tls/party-0.key",
            "tls_ca": "/etc/mpc/tls/ca.crt"
        }"#;
        let config: PartyConfig = serde_json::from_str(json).expect("parse");
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("storage_key must be 32 bytes"));
    }

    // ===== 中12: storage_key 密钥轮换（版本号） =====

    #[test]
    fn default_storage_key_version_is_1() {
        // 未指定 storage_key_version 时默认为 1
        let config: PartyConfig = serde_json::from_str(sample_config_json()).expect("parse");
        assert_eq!(config.storage_key_version, 1);
        assert_eq!(config.current_storage_key_version(), 1);
    }

    #[test]
    fn explicit_storage_key_version() {
        let json = r#"{
            "party_index": 0,
            "party_id": "party-0",
            "listen_addr": "0.0.0.0:50051",
            "peers": [],
            "storage_key": "4242424242424242424242424242424242424242424242424242424242424242",
            "storage_key_version": 3,
            "tls_cert": "/etc/mpc/tls/party-0.crt",
            "tls_key": "/etc/mpc/tls/party-0.key",
            "tls_ca": "/etc/mpc/tls/ca.crt"
        }"#;
        let config: PartyConfig = serde_json::from_str(json).expect("parse");
        config.validate().expect("validate");
        assert_eq!(config.storage_key_version, 3);
    }

    #[test]
    fn reject_storage_key_version_zero() {
        let json = r#"{
            "party_index": 0,
            "party_id": "party-0",
            "listen_addr": "0.0.0.0:50051",
            "peers": [],
            "storage_key": "4242424242424242424242424242424242424242424242424242424242424242",
            "storage_key_version": 0,
            "tls_cert": "/etc/mpc/tls/party-0.crt",
            "tls_key": "/etc/mpc/tls/party-0.key",
            "tls_ca": "/etc/mpc/tls/ca.crt"
        }"#;
        let config: PartyConfig = serde_json::from_str(json).expect("parse");
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("storage_key_version must be >= 1"));
    }

    #[test]
    fn storage_keys_map_validates_keys() {
        let json = r#"{
            "party_index": 0,
            "party_id": "party-0",
            "listen_addr": "0.0.0.0:50051",
            "peers": [],
            "storage_key": "4242424242424242424242424242424242424242424242424242424242424242",
            "storage_key_version": 2,
            "storage_keys": {
                "1": "4242424242424242424242424242424242424242424242424242424242424242",
                "2": "4343434343434343434343434343434343434343434343434343434343434343"
            },
            "tls_cert": "/etc/mpc/tls/party-0.crt",
            "tls_key": "/etc/mpc/tls/party-0.key",
            "tls_ca": "/etc/mpc/tls/ca.crt"
        }"#;
        let config: PartyConfig = serde_json::from_str(json).expect("parse");
        config.validate().expect("validate should pass");
        // resolve_storage_key_for_version 应从 storage_keys 取
        let v1 = config.resolve_storage_key_for_version(1).expect("v1");
        assert!(v1.starts_with("4242"));
        let v2 = config.resolve_storage_key_for_version(2).expect("v2");
        assert!(v2.starts_with("4343"));
    }

    #[test]
    fn resolve_storage_key_for_version_falls_back_to_storage_key() {
        // storage_keys 为空时，当前版本回退到 storage_key
        let config: PartyConfig = serde_json::from_str(sample_config_json()).expect("parse");
        config.validate().expect("validate");
        let key = config.resolve_storage_key_for_version(1).expect("v1");
        assert_eq!(key, config.storage_key);
        // 非当前版本且不在 storage_keys 中：应失败
        let err = config.resolve_storage_key_for_version(99).unwrap_err();
        assert!(err.to_string().contains("no storage_key for version 99"));
    }

    // ===== 低10: storage_key 支持 KMS/环境变量 =====

    #[test]
    fn default_storage_key_source_is_plain() {
        let config: PartyConfig = serde_json::from_str(sample_config_json()).expect("parse");
        assert_eq!(config.storage_key_source, "plain");
    }

    #[test]
    fn reject_invalid_storage_key_source() {
        let json = r#"{
            "party_index": 0,
            "party_id": "party-0",
            "listen_addr": "0.0.0.0:50051",
            "peers": [],
            "storage_key": "4242424242424242424242424242424242424242424242424242424242424242",
            "storage_key_source": "invalid",
            "tls_cert": "/etc/mpc/tls/party-0.crt",
            "tls_key": "/etc/mpc/tls/party-0.key",
            "tls_ca": "/etc/mpc/tls/ca.crt"
        }"#;
        let config: PartyConfig = serde_json::from_str(json).expect("parse");
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("invalid storage_key_source"));
    }

    #[test]
    fn env_source_skips_storage_key_in_config() {
        // env 模式：storage_key 可为空（从环境变量读取）
        let json = r#"{
            "party_index": 0,
            "party_id": "party-0",
            "listen_addr": "0.0.0.0:50051",
            "peers": [],
            "storage_key_source": "env",
            "tls_cert": "/etc/mpc/tls/party-0.crt",
            "tls_key": "/etc/mpc/tls/party-0.key",
            "tls_ca": "/etc/mpc/tls/ca.crt"
        }"#;
        let config: PartyConfig = serde_json::from_str(json).expect("parse");
        config
            .validate()
            .expect("validate should pass (env mode, storage_key optional)");
    }

    #[test]
    fn resolve_storage_key_plain_mode() {
        let config: PartyConfig = serde_json::from_str(sample_config_json()).expect("parse");
        config.validate().expect("validate");
        let key = config.resolve_storage_key().expect("resolve");
        assert_eq!(key, config.storage_key);
    }

    #[test]
    fn resolve_storage_key_kms_returns_error() {
        let json = r#"{
            "party_index": 0,
            "party_id": "party-0",
            "listen_addr": "0.0.0.0:50051",
            "peers": [],
            "storage_key_source": "kms",
            "tls_cert": "/etc/mpc/tls/party-0.crt",
            "tls_key": "/etc/mpc/tls/party-0.key",
            "tls_ca": "/etc/mpc/tls/ca.crt"
        }"#;
        let config: PartyConfig = serde_json::from_str(json).expect("parse");
        config.validate().expect("validate (kms warns but passes)");
        let err = config.resolve_storage_key().unwrap_err();
        assert!(err
            .to_string()
            .contains("KMS decryption not yet implemented"));
    }
}

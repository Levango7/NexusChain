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

/// 对端参与方配置。
#[derive(Clone, Debug, Serialize, Deserialize)]
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
    pub storage_key: String,
    /// 本方 mTLS 证书路径（PEM 格式）。
    pub tls_cert: String,
    /// 本方 mTLS 私钥路径（PEM 格式）。
    pub tls_key: String,
    /// mTLS CA 证书路径（PEM 格式，用于验证对端证书）。
    pub tls_ca: String,
}

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
        eyre::eyre!("MPC-P2-F5: failed to read config file '{}': {e}", config_path)
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
            return Err(eyre::eyre!(
                "MPC-P2-F5: listen_addr must be non-empty"
            ));
        }
        // storage_key 应为 64 hex 字符（32 字节）
        if self.storage_key.is_empty() {
            return Err(eyre::eyre!(
                "MPC-P2-F5: storage_key must be non-empty (64-char hex encoding 32 bytes \
                 for AES-256-GCM)"
            ));
        }
        let key_bytes = hex::decode(&self.storage_key).map_err(|e| {
            eyre::eyre!("MPC-P2-F5: storage_key hex decode failed: {e}")
        })?;
        if key_bytes.len() != 32 {
            return Err(eyre::eyre!(
                "MPC-P2-F5: storage_key must be 32 bytes (64 hex chars), got {} bytes",
                key_bytes.len()
            ));
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

    /// 将 storage_key 设置到环境变量 `MPC_STORAGE_KEY`（供 persistence 模块读取）。
    ///
    /// 分布式模式下，storage_key 来自配置文件而非环境变量；此方法在启动时
    /// 将配置文件的值同步到环境变量，保持 persistence 模块的 `load_storage_key` 兼容。
    ///
    /// # Safety
    /// 仅在单线程启动阶段调用（main 函数早期，未 spawn 其他线程）。
    /// `std::env::set_var` 在多线程环境下是 unsafe 的（可能引起其他线程的
    /// `std::env::var` 数据竞争），但启动阶段单线程调用是安全的。
    pub fn apply_storage_key_to_env(&self) {
        // SAFETY: 仅在 main 启动阶段单线程调用，未 spawn 任何工作线程。
        // persistence 模块后续在多线程 gRPC 处理中只读 `std::env::var`（只读安全）。
        unsafe {
            std::env::set_var("MPC_STORAGE_KEY", &self.storage_key);
        }
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
        let config: PartyConfig =
            serde_json::from_str(sample_config_json()).expect("parse config");
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
}
//! Sign（部分签名）协议骨架。
//!
//! 审计报告 §4.1 方案 A：调用 Rust `tss-lib` / `multi-party-ecdsa` 完成 GG18/GG20 签名。
//! 当前为骨架实现，返回未实现错误。

use crate::proto::mpc_crypto::{SignRequest, SignResponse};

/// 执行 GG18/GG20 部分签名（本方生成签名份额 s_i）。
///
/// # 协议轮次（GG20，7 轮）
/// 1. 各方采样 k_i，广播 R_i = k_i * G
/// 2-3. MtA（Multiplicative-to-Additive）份额交换（点对点，Paillier 同态）
/// 4. 聚合 R = Σ R_i，计算 r = R.x mod n
/// 5-6. ZK 证明（k_i 一致性 / range proof）
/// 7. 计算并广播签名份额 s_i = k_i^{-1} * (m + r * x_i)
///
/// # 参数
/// * `message_hash`：32 字节 keccak256 哈希
/// * `secret_share`：本方私钥份额（DKG 输出）
/// * `public_key`：聚合公钥（DKG 输出）
///
/// # 骨架状态
/// 仅做参数校验，密码学部分返回未实现错误。
pub async fn run_sign(req: SignRequest) -> eyre::Result<SignResponse> {
    tracing::debug!(
        session_id = %req.session_id,
        party_index = req.party_index,
        threshold = req.threshold,
        party_count = req.party_count,
        msg_hash_len = req.message_hash.len(),
        "sign: parameters received"
    );

    // === 参数校验 ===
    if req.session_id.is_empty() {
        eyre::bail!("missing session_id");
    }
    if req.message_hash.len() != 32 {
        eyre::bail!(
            "invalid message_hash: expected 32 bytes, got {}",
            req.message_hash.len()
        );
    }
    if req.secret_share.is_empty() {
        eyre::bail!("missing secret_share");
    }
    if req.public_key.is_empty() {
        eyre::bail!("missing public_key");
    }
    if req.threshold <= 0 || req.party_count <= 0 {
        eyre::bail!("invalid parameters: threshold and party_count must be positive");
    }
    if req.party_index < 0 || req.party_index >= req.party_count {
        eyre::bail!("invalid parameters: party_index out of range");
    }

    // TODO(§4.1 方案 A): 接入 tss-lib / multi-party-ecdsa 完成 GG18/GG20 Sign。
    //     1. 载入 LocalPartySign（secret_share, public_key, message_hash）
    //     2. 执行 7 轮签名（R_i 广播 → MtA → R 聚合 → ZK 证明 → s_i 计算）
    //     3. 输出 R 点 + 签名份额 s_i
    //
    // 骨架阶段返回未实现错误。
    eyre::bail!(
        "sign not implemented: pending tss-lib / multi-party-ecdsa integration (§4.1 方案 A)"
    )
}
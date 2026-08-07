//! Aggregate（签名聚合）协议骨架。
//!
//! 审计报告 §4.1 方案 A：合并各方签名份额，输出可验证的最终 ECDSA 签名。
//! 当前为骨架实现，返回未实现错误。

use crate::proto::mpc_crypto::{AggregateRequest, AggregateResponse};

/// 聚合各方签名份额，输出最终签名。
///
/// # 算法
/// 给定 R 点与各方签名份额 {s_i}，计算：
///   s = Σ s_i mod n
/// 输出签名 (r, s)，其中 r = R.x mod n。
/// 最后用聚合公钥验证 (r, s) 对 message_hash 的正确性。
///
/// # 参数
/// * `sig_shares`：各方签名份额，至少 `threshold + 1` 个
/// * `r_point`：R 点（各方应一致，由任一签名方提供）
/// * `public_key`：聚合公钥（用于最终验证）
///
/// # 骨架状态
/// 仅做参数校验，密码学部分返回未实现错误。
pub async fn run_aggregate(req: AggregateRequest) -> eyre::Result<AggregateResponse> {
    tracing::debug!(
        session_id = %req.session_id,
        shares = req.sig_shares.len(),
        msg_hash_len = req.message_hash.len(),
        "aggregate: parameters received"
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
    if req.r_point.is_empty() {
        eyre::bail!("missing r_point");
    }
    if req.public_key.is_empty() {
        eyre::bail!("missing public_key");
    }
    if req.sig_shares.is_empty() {
        eyre::bail!("missing sig_shares");
    }
    // 至少需要 1 个份额；实际阈值检查由调用方（编排层）保证 ≥ t+1。
    for (i, share) in req.sig_shares.iter().enumerate() {
        if share.is_empty() {
            eyre::bail!("empty sig_share at index {i}");
        }
    }

    // TODO(§4.1 方案 A): 接入 tss-lib / multi-party-ecdsa 完成签名聚合。
    //     1. s = Σ s_i mod n（曲线阶）
    //     2. r = R.x mod n
    //     3. 用 public_key 验证 (r, s) 对 message_hash 的 ECDSA 正确性
    //     4. 输出 signature = r || s（64 字节）+ verified 标志
    //
    // 骨架阶段返回未实现错误。
    eyre::bail!(
        "aggregate not implemented: pending tss-lib / multi-party-ecdsa integration (§4.1 方案 A)"
    )
}
//! DKG（分布式密钥生成）协议骨架。
//!
//! 审计报告 §4.1 方案 A：调用 Rust `tss-lib` / `multi-party-ecdsa` 完成 GG18/GG20 DKG。
//! 当前为骨架实现，返回未实现错误；正式接入时替换 `run_dkg` 内部逻辑。

use crate::proto::mpc_crypto::{DkgRequest, DkgResponse};

/// 执行 GG18/GG20 分布式密钥生成。
///
/// # 协议轮次（GG20，4 轮）
/// 1. 各方生成 Paillier 密钥对 (N_i, g_i) 并广播
/// 2. Feldman VSS 分发私钥份额（点对点，Paillier 同态加密）
/// 3. 交换并验证 ZK 证明（range proof / MtA 一致性）
/// 4. 广播公钥份额 X_i，聚合得到组公钥 X = Σ X_i
///
/// # 参数
/// * `threshold` (t)：任意 t+1 方可完成签名
/// * `party_count` (n)：总参与方数，需 t < n
///
/// # 骨架状态
/// 仅做参数校验，密码学部分返回 `eyre::Result` 未实现错误。
pub async fn run_dkg(req: DkgRequest) -> eyre::Result<DkgResponse> {
    tracing::debug!(
        session_id = %req.session_id,
        threshold = req.threshold,
        party_count = req.party_count,
        party_index = req.party_index,
        curve = %req.curve,
        party_ids = ?req.party_ids,
        "dkg: parameters received"
    );

    // === 参数校验 ===
    if req.session_id.is_empty() {
        eyre::bail!("missing session_id");
    }
    if req.threshold <= 0 || req.party_count <= 0 {
        eyre::bail!("invalid parameters: threshold and party_count must be positive");
    }
    if req.threshold >= req.party_count {
        eyre::bail!("invalid parameters: threshold must be < party_count");
    }
    if req.party_index < 0 || req.party_index >= req.party_count {
        eyre::bail!("invalid parameters: party_index out of range [0, party_count)");
    }
    let curve = if req.curve.is_empty() {
        "secp256k1"
    } else {
        req.curve.as_str()
    };
    if curve != "secp256k1" {
        eyre::bail!("unsupported curve: {curve} (only secp256k1 supported)");
    }
    if req.party_ids.len() as i32 != req.party_count {
        eyre::bail!(
            "invalid parameters: party_ids.len() ({}) != party_count ({})",
            req.party_ids.len(),
            req.party_count
        );
    }

    // TODO(§4.1 方案 A): 接入 tss-lib / multi-party-ecdsa 完成 GG18/GG20 DKG。
    //     1. 初始化 PartyLocalKey / LocalParty
    //     2. 执行 4 轮 DKG（Paillier 生成 → Feldman VSS → ZK 证明 → 公钥聚合）
    //     3. 输出聚合公钥 public_key + 本方私钥份额 secret_share + 各方公钥份额
    //
    // 骨架阶段返回未实现错误，由 server.rs 映射为 gRPC UNIMPLEMENTED。
    eyre::bail!(
        "dkg not implemented: pending tss-lib / multi-party-ecdsa integration (§4.1 方案 A)"
    )
}
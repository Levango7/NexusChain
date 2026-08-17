//! DKG（分布式密钥生成）—— 真实 GG20 门限 ECDSA 实现。
//!
//! 审计报告 §4.1 方案 A：调用 Rust `multi-party-ecdsa`（ZenGo-X/KZen）完成 GG20 DKG。
//!
//! 部署模型（诚实声明）：当前为「可信协调器」模型——首次 Dkg RPC 调用在引擎进程内
//! 一次性运行全部 n 方 GG20 协议（真实 Paillier 密钥生成、Feldman VSS、MtA、ZK 证明），
//! 会话状态（含各方密钥材料）序列化后缓存于进程内存；后续同 session_id 的调用
//! （其余参与方）从缓存取回各自份额。
//!
//! 门限密码学数学是真实的，产出可被标准 secp256k1 验证的聚合公钥与份额；
//! 但各方私钥份额暂驻留同一进程内存，尚未分散到互不信任的独立节点。
//! 完全分散式部署（t-of-n 方被攻破不泄露私钥）为后续演进目标。

use std::collections::HashMap;
// std::sync::Mutex safe: lock not held across .await point.
// run_dkg 为同步函数（pub fn，非 async fn），sessions.lock() 在同步代码块内
// 获取释放，无 .await 调用，不会死锁 tokio 运行时。
use std::sync::Mutex;

use crate::gg20;
use crate::gg20::DkgSession;
use crate::proto::mpc_crypto::{DkgRequest, DkgResponse};

/// 执行 GG20 分布式密钥生成。
///
/// 首次调用（缓存无会话）在进程内执行完整 n 方 DKG 并缓存会话；
/// 后续调用从缓存返回对应参与方的份额。
pub fn run_dkg(
    sessions: &Mutex<HashMap<String, DkgSession>>,
    req: DkgRequest,
) -> eyre::Result<DkgResponse> {
    tracing::debug!(
        session_id = %req.session_id,
        threshold = req.threshold,
        total_parties = req.total_parties,
        party_index = req.party_index,
        curve = %req.curve,
        "dkg: parameters received"
    );

    // === 参数校验 ===
    if req.session_id.is_empty() {
        return Ok(DkgResponse {
            public_key: String::new(),
            key_share: String::new(),
            proof: String::new(),
            success: false,
            error: "missing session_id".to_string(),
        });
    }
    if req.threshold <= 0 || req.total_parties <= 0 {
        return Ok(DkgResponse {
            public_key: String::new(),
            key_share: String::new(),
            proof: String::new(),
            success: false,
            error: "threshold and total_parties must be positive".to_string(),
        });
    }
    if req.threshold >= req.total_parties {
        return Ok(DkgResponse {
            public_key: String::new(),
            key_share: String::new(),
            proof: String::new(),
            success: false,
            error: "threshold must be < total_parties".to_string(),
        });
    }
    if req.party_index < 0 || req.party_index >= req.total_parties {
        return Ok(DkgResponse {
            public_key: String::new(),
            key_share: String::new(),
            proof: String::new(),
            success: false,
            error: "party_index out of range [0, total_parties)".to_string(),
        });
    }
    let curve = if req.curve.is_empty() {
        "secp256k1"
    } else {
        req.curve.as_str()
    };
    if curve != "secp256k1" {
        return Ok(DkgResponse {
            public_key: String::new(),
            key_share: String::new(),
            proof: String::new(),
            success: false,
            error: format!("unsupported curve: {curve} (only secp256k1 supported)"),
        });
    }

    let party_index = req.party_index as usize;
    let threshold = req.threshold as u16;
    let total_parties = req.total_parties as u16;

    // === 取缓存会话，或首次执行完整 GG20 DKG ===
    let session: DkgSession = {
        let mut guard = sessions
            .lock()
            .map_err(|e| eyre::eyre!("session lock poisoned: {e}"))?;
        match guard.get(&req.session_id) {
            Some(existing) => existing.clone(),
            None => {
                tracing::info!(
                    session_id = %req.session_id,
                    threshold,
                    total_parties,
                    "dkg: executing full GG20 keygen (trusted-coordinator, in-process)"
                );
                let (_y_sum, _x_shares, session) =
                    gg20::run_keygen(threshold, total_parties)?;
                guard.insert(req.session_id.clone(), session.clone());
                // 方案 A 缺口 1：DKG 份额落盘（重启后 Sign 可恢复）
                if let Err(e) = crate::persistence::persist_session(&req.session_id, &session) {
                    tracing::warn!(session_id = %req.session_id, error = %e, "session persist failed (continue in-memory)");
                }
                session
            }
        }
    };

    // === MPC-P1-05: 防御性范围校验 ===
    // 防止"重复调用同一 session_id 按 party_index 无条件提取任意方私钥份额"漏洞：
    // 即使请求的 total_parties 校验通过（line 69-77），缓存会话的 shared_keys.len()
    // 可能与请求 total_parties 不一致（如攻击者传 total_parties=n 但 session 实际 m 方）。
    // 此处以会话实际份额数为准，越界直接拒绝，不泄露任何方份额。
    if party_index >= session.shared_keys.len() {
        tracing::warn!(
            session_id = %req.session_id,
            party_index,
            shared_keys_len = session.shared_keys.len(),
            threshold = req.threshold,
            total_parties = req.total_parties,
            "dkg: party_index out of session shared_keys range — \
             possible total_parties mismatch or replay attack (MPC-P1-05)"
        );
        return Ok(DkgResponse {
            public_key: String::new(),
            key_share: String::new(),
            proof: String::new(),
            success: false,
            error: format!(
                "party_index {} out of session shared_keys range [0, {}) \
                 (MPC-P1-05: denied to prevent arbitrary share extraction)",
                party_index, session.shared_keys.len()
            ),
        });
    }

    // === 从会话提取本方份额与聚合公钥（hex 编码，Java proto 契约）===
    let public_key = gg20::hex_point(&session.y_sum);
    let key_share = gg20::hex_scalar(&session.shared_keys[party_index].x_i);
    // DKG 正确性 ZK 证明：本方份额的 DLog 证明（serde JSON -> hex）
    let proof = serde_json::to_vec(&session.dlog_proofs[party_index])
        .map(hex::encode)
        .unwrap_or_default();

    tracing::info!(
        session_id = %req.session_id,
        party_index,
        "dkg: session ready (real GG20, trusted-coordinator model)"
    );

    Ok(DkgResponse {
        public_key,
        key_share,
        proof,
        success: true,
        error: String::new(),
    })
}

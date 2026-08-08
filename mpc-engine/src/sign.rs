//! Sign（部分签名）—— 真实 GG20 门限 ECDSA 实现。
//!
//! 审计报告 §4.1 方案 A：调用 Rust `multi-party-ecdsa`（ZenGo-X/KZen）完成 GG20 签名。
//!
//! 部署模型（诚实声明）：可信协调器模型。GG20 签名需要全部 t+1 个签名方交互式协作，
//! 而当前各参与方密钥材料驻留引擎进程内，故首次 Sign RPC 在进程内一次性执行完整
//! GG20 签名协议（真实 Paillier 解密、MtA、ZK 证明），并缓存运行结果；
//! 后续同 session_id + 消息的调用按 party_index 分发对应份额。
//!
//! `partial_signature` 返回本方部分签名 s_i（hex），聚合由 Aggregate RPC 完成。

use std::collections::HashMap;
use std::sync::Mutex;

use crate::gg20;
use crate::gg20::{DkgSession, SignCache};
use crate::proto::mpc_crypto::{SignRequest, SignResponse};

/// 执行 GG20 签名协议（可信协调器，进程内运行全部签名方）。
pub fn run_sign(
    sessions: &Mutex<HashMap<String, DkgSession>>,
    sign_runs: &Mutex<HashMap<String, SignCache>>,
    req: SignRequest,
) -> eyre::Result<SignResponse> {
    tracing::debug!(
        session_id = %req.session_id,
        party_index = req.party_index,
        msg_hash_len = req.message_hash.len(),
        "sign: parameters received"
    );

    let fail = |error: String| SignResponse {
        partial_signature: String::new(),
        proof: String::new(),
        success: false,
        error,
    };

    // === 参数校验 ===
    if req.session_id.is_empty() {
        return Ok(fail("missing session_id".to_string()));
    }
    // message_hash 为 hex 编码的 32 字节（64 hex 字符）
    let message_bytes = hex::decode(&req.message_hash)
        .map_err(|e| eyre::eyre!("invalid message_hash hex: {e}"))?;
    if message_bytes.len() != 32 {
        return Ok(fail(format!(
            "invalid message_hash: expected 32 bytes, got {}",
            message_bytes.len()
        )));
    }
    if req.party_index < 0 {
        return Ok(fail("party_index out of range".to_string()));
    }

    // === 取缓存的签名运行；无则执行完整 GG20 签名 ===
    let cache: SignCache = {
        let mut guard = sign_runs
            .lock()
            .map_err(|e| eyre::eyre!("sign_runs lock poisoned: {e}"))?;

        if let Some(existing) = guard.get(&req.session_id) {
            if existing.message_hash != req.message_hash {
                return Ok(fail(
                    "message_hash mismatch with cached signing run for this session".to_string(),
                ));
            }
            existing.clone()
        } else {
            // 取 DKG 会话
            let session: DkgSession = {
                let s_guard = sessions
                    .lock()
                    .map_err(|e| eyre::eyre!("session lock poisoned: {e}"))?;
                s_guard
                    .get(&req.session_id)
                    .cloned()
                    .ok_or_else(|| {
                        eyre::eyre!(
                            "no DKG session found for session_id {} (run Dkg first)",
                            req.session_id
                        )
                    })?
            };

            let threshold = session.params.threshold as usize;
            let signer_indices: Vec<usize> = (0..=threshold).collect();
            let message_bn = gg20::message_hash_to_bigint(&message_bytes);

            tracing::info!(
                session_id = %req.session_id,
                signers = ?signer_indices,
                "sign: executing full GG20 signing protocol (trusted-coordinator, in-process)"
            );

            let output = gg20::run_sign(&session, &signer_indices, &message_bn)?;

            let cache = SignCache {
                r_point: output.r_point,
                signature: output.signature,
                partial_shares: output.partial_shares,
                message_hash: req.message_hash.clone(),
            };
            guard.insert(req.session_id.clone(), cache.clone());
            cache
        }
    };

    // === 按 party_index 分发份额 ===
    let party_index = req.party_index as usize;
    if party_index >= cache.partial_shares.len() {
        return Ok(fail(format!(
            "party_index {party_index} not among signing parties (0..{})",
            cache.partial_shares.len()
        )));
    }
    let partial_signature = gg20::hex_scalar(&cache.partial_shares[party_index]);

    Ok(SignResponse {
        partial_signature,
        proof: gg20::hex_point(&cache.r_point), // 附带 nonce 点 R 供聚合重算 r
        success: true,
        error: String::new(),
    })
}

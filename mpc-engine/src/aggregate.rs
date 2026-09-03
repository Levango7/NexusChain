//! Aggregate（签名聚合）—— 真实 GG20 门限 ECDSA 的聚合与验证。
//!
//! 审计报告 §4.1 方案 A：合并各方签名份额，输出可验证的最终 ECDSA 签名。
//!
//! 可信协调器模型下，Sign RPC 已在进程内执行完整 GG20 协议并缓存运行结果；
//! 本聚合步骤完成：份额求和、低 s 归一化（ECDSA 规范）、使用聚合公钥对消息哈希
//! 做标准 secp256k1 验证，输出 signature = r || s（hex）。
//!
//! **S4-b 修复（验签公钥绑定 DKG 聚合公钥）**：旧实现用调用方传入的
//! `req.public_key` 做最终验签——攻击者可用自控公钥 + 自控部分份额构造
//! "验证通过"的签名，产出对 DKG 聚合公钥无意义的假签名。现改为：
//!   * Sign 阶段由引擎把 DKG 会话的聚合公钥（`session.y_sum`）写入
//!     `SignCache.aggregate_public_key`；
//!   * Aggregate 验签**只信任缓存公钥**，并对 `req.public_key` 做一致性
//!     绑定校验（fail-closed：不匹配直接拒绝）——验签公钥恢复与 DKG
//!     产物的密码学绑定，与 distributed.rs 分散式路径
//!     （`LocalKey.public_key` 全体一致）同一信任根基。

use curv::elliptic::curves::{secp256_k1::Secp256k1, Scalar};

use crate::gg20;
use crate::gg20::SignCache;
use crate::proto::mpc_crypto::{AggregateRequest, AggregateResponse};
use std::collections::HashMap;
// std::sync::Mutex safe: lock not held across .await point.
// run_aggregate 为同步函数（pub fn，非 async fn），sign_runs.lock() 在同步代码块
// 内获取释放，无 .await 调用，不会死锁 tokio 运行时。
use std::sync::Mutex;

/// 聚合各方签名份额，输出最终签名并验证。
pub fn run_aggregate(
    sign_runs: &Mutex<HashMap<String, SignCache>>,
    req: AggregateRequest,
) -> eyre::Result<AggregateResponse> {
    tracing::debug!(
        session_id = %req.session_id,
        shares = req.partial_signatures.len(),
        "aggregate: parameters received"
    );

    let fail = |error: String| AggregateResponse {
        signature: String::new(),
        r: String::new(),
        s: String::new(),
        recovery_id: 0,
        success: false,
        error,
    };

    // === 参数校验 ===
    if req.session_id.is_empty() {
        return Ok(fail("missing session_id".to_string()));
    }
    let message_bytes =
        hex::decode(&req.message_hash).map_err(|e| eyre::eyre!("invalid message_hash hex: {e}"))?;
    if message_bytes.len() != 32 {
        return Ok(fail(format!(
            "invalid message_hash: expected 32 bytes, got {}",
            message_bytes.len()
        )));
    }
    if req.public_key.is_empty() {
        return Ok(fail("missing public_key".to_string()));
    }
    if req.partial_signatures.is_empty() {
        return Ok(fail("missing partial_signatures".to_string()));
    }

    // === 取缓存的签名运行，校验消息哈希一致 ===
    let cache: SignCache = {
        let guard = sign_runs
            .lock()
            .map_err(|e| eyre::eyre!("sign_runs lock poisoned: {e}"))?;
        match guard.get(&req.session_id) {
            Some(c) if c.message_hash == req.message_hash => c.clone(),
            Some(_) => {
                return Ok(fail(
                    "message_hash mismatch with cached signing run".to_string(),
                ))
            }
            None => {
                return Ok(fail(format!(
                    "no signing run cached for session_id {} (run Sign first)",
                    req.session_id
                )))
            }
        }
    };

    // === S4-b: 验签公钥一致性绑定校验（fail-closed） ===
    // 请求公钥必须与 Sign 阶段由引擎写入缓存的 DKG 聚合公钥一致；
    // 不一致（公钥冒用/伪造）直接拒绝，不产出签名。解析失败的 hex
    // 同样拒绝（fail-closed，不做模糊匹配）。
    let requested_pk = gg20::point_from_hex(&req.public_key)?;
    if requested_pk != cache.aggregate_public_key {
        tracing::warn!(
            session_id = %req.session_id,
            "S4-b: aggregate rejected — request public_key does not match \
             the DKG aggregate public key bound to this signing run"
        );
        return Ok(fail(
            "public_key mismatch: request public_key does not match the DKG \
             aggregate public key for this session (S4-b: verification key \
             is bound to the DKG output, attacker-controlled keys rejected)"
                .to_string(),
        ));
    }

    // === 聚合：s = Σ sig_shares mod n ===
    let n = Scalar::<Secp256k1>::group_order();
    let mut s_acc: Scalar<Secp256k1> = gg20::scalar_from_hex(&req.partial_signatures[0])?;
    for (i, share_hex) in req.partial_signatures.iter().enumerate().skip(1) {
        let s_i = gg20::scalar_from_hex(share_hex)
            .map_err(|e| eyre::eyre!("invalid partial_signature[{i}]: {e}"))?;
        s_acc = &s_acc + &s_i;
    }

    // === 低 s 归一化（ECDSA 规范：s 应 ≤ n/2）===
    let s_bn = s_acc.to_bigint();
    let half = n / curv::BigInt::from(2u32);
    let s = if s_bn > half {
        Scalar::<Secp256k1>::from(&(n - &s_bn))
    } else {
        s_acc
    };

    // === 标准 secp256k1 验证（S4-b: 只用 Sign 阶段绑定的 DKG 聚合公钥）===
    let message_bn = gg20::message_hash_to_bigint(&message_bytes);
    let r = cache.signature.r.clone();
    let sig = multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::party_i::SignatureRecid {
        r: r.clone(),
        s: s.clone(),
        recid: 0,
    };
    let verified = multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::party_i::verify(
        &sig,
        &cache.aggregate_public_key,
        &message_bn,
    )
    .is_ok();

    // === 组装签名（hex）===
    let signature = format!("{}{}", gg20::hex_scalar(&r), gg20::hex_scalar(&s));

    tracing::info!(
        session_id = %req.session_id,
        verified,
        "aggregate: signature assembled"
    );

    Ok(AggregateResponse {
        signature,
        r: gg20::hex_scalar(&r),
        s: gg20::hex_scalar(&s),
        recovery_id: cache.signature.recid as i32,
        success: verified,
        error: String::new(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gg20::SignCache;
    use crate::proto::mpc_crypto::{DkgRequest, SignRequest};
    use curv::elliptic::curves::Point;

    /// 构造真实 GG20 DKG+Sign 缓存（t=1, n=2）。
    ///
    /// 返回 `(sign_runs, aggregate_pk_hex, partial_shares_hex)`：
    /// * `sign_runs`：内含 SignCache 的签名运行缓存（Aggregate 的输入状态）
    /// * `aggregate_pk_hex`：DKG 聚合公钥（hex，与 SignCache 绑定值一致）
    /// * `partial_shares_hex`：全部签名方的部分签名份额（hex）
    fn setup_sign_run(
        session_id: &str,
        msg_hash_hex: &str,
    ) -> (Mutex<HashMap<String, SignCache>>, String, Vec<String>) {
        let sessions = Mutex::new(HashMap::new());
        let dkg_resp = crate::dkg::run_dkg(
            &sessions,
            DkgRequest {
                session_id: session_id.to_string(),
                threshold: 1,
                total_parties: 2,
                party_index: 0,
                curve: "secp256k1".to_string(),
                peer_endpoints: vec![],
            },
        )
        .expect("run_dkg");
        assert!(dkg_resp.success, "dkg failed: {}", dkg_resp.error);

        let sign_runs = Mutex::new(HashMap::new());
        let sign_resp = crate::sign::run_sign(
            &sessions,
            &sign_runs,
            SignRequest {
                session_id: session_id.to_string(),
                public_key: dkg_resp.public_key.clone(),
                key_share: dkg_resp.key_share.clone(),
                message_hash: msg_hash_hex.to_string(),
                party_index: 0,
                peer_endpoints: vec![],
            },
        )
        .expect("run_sign");
        assert!(sign_resp.success, "sign failed: {}", sign_resp.error);

        let (aggregate_pk_hex, partial_shares_hex) = {
            let guard = sign_runs.lock().expect("sign_runs lock");
            let cache = guard.get(session_id).expect("sign cache inserted");
            (
                gg20::hex_point(&cache.aggregate_public_key),
                cache.partial_shares.iter().map(gg20::hex_scalar).collect(),
            )
        };
        (sign_runs, aggregate_pk_hex, partial_shares_hex)
    }

    /// 回归保护：正确公钥 + 正确份额 → 聚合成功（正常链路不受 S4-b 影响）。
    #[test]
    fn aggregate_with_correct_public_key_succeeds() {
        let session_id = "s4b-happy-path";
        let msg_hash = "41".repeat(32);
        let (sign_runs, pk_hex, shares) = setup_sign_run(session_id, &msg_hash);

        let resp = run_aggregate(
            &sign_runs,
            AggregateRequest {
                session_id: session_id.to_string(),
                public_key: pk_hex,
                message_hash: msg_hash,
                partial_signatures: shares,
            },
        )
        .expect("run_aggregate");
        assert!(resp.success, "aggregate failed: {}", resp.error);
        assert!(!resp.signature.is_empty());
    }

    /// S4-b 回归：攻击者自控公钥（P_a = x_a·G，x_a 任选）→ 聚合必须
    /// fail-closed 拒绝（错误信息含 mismatch 说明），不产出签名。
    ///
    /// 修复前：验签用 `req.public_key`，攻击者可用自控公钥 + 任意份额
    /// 伪造 `success=true` 的假签名。
    #[test]
    fn aggregate_rejects_attacker_controlled_public_key() {
        let session_id = "s4b-attacker-pk";
        let msg_hash = "42".repeat(32);
        let (sign_runs, _pk_hex, shares) = setup_sign_run(session_id, &msg_hash);

        // 攻击者公钥：x_a·G（x_a = 12345，与 DKG 聚合公钥必然不同的概率可忽略）
        let attacker_pk_hex = {
            let x_a = Scalar::<Secp256k1>::from(&curv::BigInt::from(12345u32));
            gg20::hex_point(&(Point::<Secp256k1>::generator() * &x_a))
        };

        let resp = run_aggregate(
            &sign_runs,
            AggregateRequest {
                session_id: session_id.to_string(),
                public_key: attacker_pk_hex,
                message_hash: msg_hash,
                partial_signatures: shares,
            },
        )
        .expect("run_aggregate");
        assert!(
            !resp.success,
            "S4-b: attacker-controlled public_key must be rejected"
        );
        assert!(
            resp.error.contains("public_key mismatch"),
            "S4-b: rejection reason should be the binding check, got: {}",
            resp.error
        );
        assert!(resp.signature.is_empty());
    }

    /// S4-b 语义闭环：正确公钥 + 被篡改的份额 → 公钥绑定通过但**验签失败**。
    ///
    /// 证明绑定后验签仍在工作（绑定不是摆设）：份额求和不对时，
    /// `verify` 对 DKG 聚合公钥必然失败，`success=false`。
    #[test]
    fn aggregate_with_correct_key_but_tampered_shares_fails_verification() {
        let session_id = "s4b-tampered-shares";
        let msg_hash = "43".repeat(32);
        let (sign_runs, pk_hex, mut shares) = setup_sign_run(session_id, &msg_hash);

        // 篡改第一个份额（+1 破坏 s = Σ s_i 的正确性）
        let first = gg20::scalar_from_hex(&shares[0]).expect("scalar parse");
        let tampered = &first + &Scalar::<Secp256k1>::from(&curv::BigInt::from(1u32));
        shares[0] = gg20::hex_scalar(&tampered);

        let resp = run_aggregate(
            &sign_runs,
            AggregateRequest {
                session_id: session_id.to_string(),
                public_key: pk_hex,
                message_hash: msg_hash,
                partial_signatures: shares,
            },
        )
        .expect("run_aggregate");
        assert!(
            !resp.success,
            "tampered partial shares must fail verification against the DKG aggregate key"
        );
    }
}

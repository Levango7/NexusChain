//! Aggregate（签名聚合）—— 真实 GG20 门限 ECDSA 的聚合与验证。
//!
//! 审计报告 §4.1 方案 A：合并各方签名份额，输出可验证的最终 ECDSA 签名。
//!
//! 可信协调器模型下，Sign RPC 已在进程内执行完整 GG20 协议并缓存运行结果；
//! 本聚合步骤完成：份额求和、低 s 归一化（ECDSA 规范）、使用聚合公钥对消息哈希
//! 做标准 secp256k1 验证，输出 signature = r || s（hex）。

use curv::arithmetic::traits::Converter;
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

    // === 标准 secp256k1 验证（使用缓存的 r 点与公钥）===
    let public_key = gg20::point_from_hex(&req.public_key)?;
    let message_bn = gg20::message_hash_to_bigint(&message_bytes);
    let r = cache.signature.r.clone();
    let sig = multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::party_i::SignatureRecid {
        r: r.clone(),
        s: s.clone(),
        recid: 0,
    };
    let verified = multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::party_i::verify(
        &sig,
        &public_key,
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

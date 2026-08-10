//! GG20 全协议封装（真实门限 ECDSA）。
//!
//! 基于 ZenGo-X/KZen `multi-party-ecdsa`（GG18/GG20）在进程内运行完整协议：
//!   * DKG：4 轮（Paillier 密钥生成 → Feldman VSS → ZK 证明 → 公钥聚合）
//!   * Sign：7 轮（R_i 广播 → MtA → R 聚合 → ZK 证明 → s_i 计算 → 聚合）
//!
//! 部署模型说明（诚实声明）：本模块在单一进程内模拟全部 n 个参与方运行 GG20
//! 协议，属于「可信协调器」模型——门限密码学的数学是真实的（真实 Paillier、
//! Feldman VSS、MtA、ZK 证明，产出可被标准 secp256k1 验证的签名），但各方
//! 私钥份额暂驻留同一进程，而非分散在互不信任的独立节点上。要达到完全的
//! 门限安全（t-of-n 方被攻破不泄露私钥），需将各方协议执行拆分到独立节点
//! 并经 mpc_signer.proto 传输层路由消息（见 README 的演进路线）。

use serde::{Deserialize, Serialize};

use multi_party_ecdsa::protocols::multi_party_ecdsa::gg_2020::party_i::{
    verify, KeyGenBroadcastMessage1, KeyGenDecommitMessage1, Keys, LocalSignature, Parameters,
    SignatureRecid, SignKeys,
};
use multi_party_ecdsa::utilities::mta::{MessageA, MessageB};

use curv::arithmetic::traits::Converter;
use curv::cryptographic_primitives::hashing::{Digest, DigestExt};
use curv::cryptographic_primitives::proofs::sigma_dlog::DLogProof;
use curv::cryptographic_primitives::proofs::sigma_valid_pedersen::PedersenProof;
use curv::cryptographic_primitives::secret_sharing::feldman_vss::VerifiableSS;
use curv::elliptic::curves::{secp256_k1::Secp256k1, Point, Scalar};
use paillier::EncryptionKey;
use sha2::Sha256;
use zk_paillier::zkproofs::DLogStatement;

/// DKG 完成后的完整会话状态（签名阶段所需的全部材料）。
///
/// 序列化存储，供 Sign 阶段重建。
#[derive(Clone, Serialize, Deserialize)]
pub struct DkgSession {
    pub params: Parameters,
    pub party_keys: Vec<Keys>,
    pub shared_keys: Vec<SharedKeysSerde>,
    pub pk_vec: Vec<Point<Secp256k1>>,
    pub y_sum: Point<Secp256k1>,
    pub vss_scheme: VerifiableSS<Secp256k1>,
    pub ek_vec: Vec<EncryptionKey>,
    pub dlog_statement_vec: Vec<DLogStatement>,
    /// 各方份额的 DLog 证明（DKG 第 3 轮产出），供 DKG 响应携带真实 ZK 证明。
    pub dlog_proofs: Vec<DLogProof<Secp256k1, Sha256>>,
}

/// SharedKeys 的 serde 包装（原类型无 Serialize）。
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SharedKeysSerde {
    pub x_i: Scalar<Secp256k1>,
    pub y_i: Point<Secp256k1>,
}

/// GG20 签名输出。
///
/// 协调器模型下，进程内一次性跑完全部签名方协议，此结构同时暴露：
///   * `r_point`：nonce 点 R（各方一致，聚合时用于重算 r = R.x mod n）
///   * `partial_shares`：各签名方的部分签名份额 s_i
///   * `signature`：最终聚合签名（已库内验证）
#[derive(Clone)]
pub struct Gg20SignOutput {
    pub r_point: Point<Secp256k1>,
    #[allow(dead_code)] // 公开 API：分散式协调器演进时分发部分份额
    pub partial_shares: Vec<Scalar<Secp256k1>>,
    pub signature: SignatureRecid,
}

/// 运行完整 GG20 分布式密钥生成（n 方，阈值 t）。
///
/// 返回聚合公钥、各方私钥份额与会话状态。
pub fn run_keygen(t: u16, n: u16) -> eyre::Result<(Point<Secp256k1>, Vec<Scalar<Secp256k1>>, DkgSession)> {
    let params = Parameters { threshold: t, share_count: n };
    let n_us = n as usize;

    // Phase 1: 各方生成 Paillier 密钥对并广播承诺
    let party_keys_vec: Vec<Keys> = (0..n_us).map(Keys::create).collect();
    let (bc1_vec, decom_vec): (Vec<KeyGenBroadcastMessage1>, Vec<KeyGenDecommitMessage1>) =
        party_keys_vec
            .iter()
            .map(|k| k.phase1_broadcast_phase3_proof_of_correct_key_proof_of_correct_h1h2())
            .unzip();

    let e_vec: Vec<EncryptionKey> = bc1_vec.iter().map(|bc1| bc1.e.clone()).collect();
    let h1_h2_n_tilde_vec: Vec<DLogStatement> =
        bc1_vec.iter().map(|bc1| bc1.dlog_statement.clone()).collect();
    let y_vec: Vec<Point<Secp256k1>> = (0..n_us).map(|i| decom_vec[i].y_i.clone()).collect();
    let mut y_vec_iter = y_vec.iter();
    let head = y_vec_iter.next().ok_or_else(|| eyre::eyre!("empty party set"))?;
    let tail = y_vec_iter;
    let y_sum = tail.fold(head.clone(), |acc, x| acc + x);

    // Phase 2: Feldman VSS 分发私钥份额 + 验证承诺与 ZK 证明
    let vss_result: Vec<_> = party_keys_vec
        .iter()
        .map(|k| {
            k.phase1_verify_com_phase3_verify_correct_key_verify_dlog_phase2_distribute(
                &params, &decom_vec, &bc1_vec,
            )
            .expect("VSS distribution failed")
        })
        .collect();

    let mut vss_scheme_vec = Vec::new();
    let mut secret_shares_vec = Vec::new();
    let mut index_vec = Vec::new();
    for (vss_scheme, secret_shares, index) in vss_result {
        vss_scheme_vec.push(vss_scheme);
        secret_shares_vec.push(secret_shares);
        index_vec.push(index as u16);
    }

    // 每方聚合收到的份额构造本地密钥对 + DLog 证明
    let party_shares: Vec<Vec<Scalar<Secp256k1>>> = (0..n_us)
        .map(|i| {
            (0..n_us)
                .map(|j| secret_shares_vec[j][i].clone())
                .collect()
        })
        .collect();

    let mut shared_keys_vec = Vec::new();
    let mut dlog_proof_vec = Vec::new();
    for (i, key) in party_keys_vec.iter().enumerate() {
        let (shared_keys, dlog_proof) = key
            .phase2_verify_vss_construct_keypair_phase3_pok_dlog(
                &params,
                &y_vec,
                &party_shares[i],
                &vss_scheme_vec,
                (&index_vec[i] + 1).into(),
            )
            .map_err(|e| eyre::eyre!("keypair construction failed: {e:?}"))?;
        shared_keys_vec.push(shared_keys);
        dlog_proof_vec.push(dlog_proof);
    }

    let pk_vec: Vec<Point<Secp256k1>> =
        (0..n_us).map(|i| dlog_proof_vec[i].pk.clone()).collect();

    Keys::verify_dlog_proofs_check_against_vss(&params, &dlog_proof_vec, &y_vec, &vss_scheme_vec)
        .map_err(|e| eyre::eyre!("DLog proof verification failed: {e:?}"))?;

    // 输出各方私钥份额 x_i（全部 n 方，调用方按 party_index 取用）
    let x_shares: Vec<Scalar<Secp256k1>> = (0..n_us)
        .map(|i| shared_keys_vec[i].x_i.clone())
        .collect();

    let session = DkgSession {
        params,
        party_keys: party_keys_vec,
        shared_keys: shared_keys_vec
            .iter()
            .map(|sk| SharedKeysSerde { x_i: sk.x_i.clone(), y_i: sk.y.clone() })
            .collect(),
        pk_vec,
        y_sum: y_sum.clone(),
        vss_scheme: vss_scheme_vec[0].clone(),
        ek_vec: e_vec,
        dlog_statement_vec: h1_h2_n_tilde_vec,
        dlog_proofs: dlog_proof_vec.clone(),
    };

    Ok((y_sum, x_shares, session))
}

/// 运行完整 GG20 签名协议（使用 DKG 会话状态，选 ttag 个签名方）。
///
/// `signer_indices`：参与签名的方索引（基于 0，长度 = ttag，需 > threshold）。
/// `message_bn`：32 字节消息哈希对应的 BigInt。
///
/// 返回可被标准 secp256k1 验证的最终签名，以及 nonce 点与部分签名份额。
pub fn run_sign(
    session: &DkgSession,
    signer_indices: &[usize],
    message_bn: &curv::BigInt,
) -> eyre::Result<Gg20SignOutput> {
    let DkgSession {
        params,
        party_keys: party_keys_vec,
        pk_vec,
        y_sum: y,
        vss_scheme,
        ek_vec,
        dlog_statement_vec,
        ..
    } = session;

    let t = params.threshold as usize;
    let ttag = signer_indices.len();
    eyre::ensure!(ttag > t, "need more signers ({ttag}) than threshold ({t})");

    // 将 t-of-n 份额转换为 t-of-ttag 签名份额
    let g_w_vec = SignKeys::g_w_vec(pk_vec, signer_indices, vss_scheme);

    let private_vec: Vec<Scalar<Secp256k1>> = (0..session.shared_keys.len())
        .map(|i| session.shared_keys[i].x_i.clone())
        .collect();

    // 各签名方创建签名密钥
    let sign_keys_vec: Vec<SignKeys> = (0..ttag)
        .map(|i| SignKeys::create(&private_vec[signer_indices[i]], vss_scheme, signer_indices[i], signer_indices))
        .collect();

    // Phase 1: 各方广播 g^gamma_i 承诺 + Paillier 加密 k_i
    let (bc1_vec, decommit_vec1): (Vec<_>, Vec<_>) =
        sign_keys_vec.iter().map(|k| k.phase1_broadcast()).unzip();

    let signers_dlog_statements: Vec<DLogStatement> = (0..ttag)
        .map(|i| dlog_statement_vec[signer_indices[i]].clone())
        .collect();

    let m_a_vec: Vec<_> = sign_keys_vec
        .iter()
        .enumerate()
        .map(|(i, k)| MessageA::a(&k.k_i, &party_keys_vec[signer_indices[i]].ek, &signers_dlog_statements))
        .collect();

    // Phase 2: MtA 交换（各方向其余各方发送 MessageB）
    let mut m_b_gamma_vec_all = Vec::new();
    let mut beta_vec_all = Vec::new();
    let mut m_b_w_vec_all = Vec::new();
    let mut ni_vec_all = Vec::new();

    for i in 0..ttag {
        let mut m_b_gamma_vec = Vec::new();
        let mut beta_vec = Vec::new();
        let mut m_b_w_vec = Vec::new();
        let mut ni_vec = Vec::new();
        for j in 0..ttag - 1 {
            let ind = if j < i { j } else { j + 1 };
            let (m_b_gamma, beta_gamma, _beta_rand, _beta_tag) = MessageB::b(
                &sign_keys_vec[ind].gamma_i,
                &ek_vec[signer_indices[i]],
                m_a_vec[i].0.clone(),
                &signers_dlog_statements,
            )
            .map_err(|e| eyre::eyre!("MtA gamma failed: {e:?}"))?;
            let (m_b_w, beta_wi, _, _) = MessageB::b(
                &sign_keys_vec[ind].w_i,
                &ek_vec[signer_indices[i]],
                m_a_vec[i].0.clone(),
                &signers_dlog_statements,
            )
            .map_err(|e| eyre::eyre!("MtA w failed: {e:?}"))?;
            m_b_gamma_vec.push(m_b_gamma);
            beta_vec.push(beta_gamma);
            m_b_w_vec.push(m_b_w);
            ni_vec.push(beta_wi);
        }
        m_b_gamma_vec_all.push(m_b_gamma_vec);
        beta_vec_all.push(beta_vec);
        m_b_w_vec_all.push(m_b_w_vec);
        ni_vec_all.push(ni_vec);
    }

    // 完成 MtA：计算 alpha / miu
    let mut alpha_vec_all = Vec::new();
    let mut miu_vec_all = Vec::new();
    for i in 0..ttag {
        let mut alpha_vec = Vec::new();
        let mut miu_vec = Vec::new();
        for j in 0..ttag - 1 {
            let m_b = m_b_gamma_vec_all[i][j].clone();
            let alpha_ij_gamma = m_b
                .verify_proofs_get_alpha(&party_keys_vec[signer_indices[i]].dk, &sign_keys_vec[i].k_i)
                .map_err(|e| eyre::eyre!("alpha gamma verify failed: {e:?}"))?;
            let m_b = m_b_w_vec_all[i][j].clone();
            let alpha_ij_wi = m_b
                .verify_proofs_get_alpha(&party_keys_vec[signer_indices[i]].dk, &sign_keys_vec[i].k_i)
                .map_err(|e| eyre::eyre!("alpha w verify failed: {e:?}"))?;
            alpha_vec.push(alpha_ij_gamma.0);
            miu_vec.push(alpha_ij_wi.0);
        }
        alpha_vec_all.push(alpha_vec);
        miu_vec_all.push(miu_vec);
    }

    // Phase 2 续：计算 delta_i / sigma_i
    let mut delta_vec = Vec::new();
    let mut sigma_vec = Vec::new();
    for i in 0..ttag {
        let beta_vec: Vec<Scalar<Secp256k1>> = (0..ttag - 1)
            .map(|j| {
                let ind1 = if j < i { j } else { j + 1 };
                let ind2 = if j < i { i - 1 } else { i };
                beta_vec_all[ind1][ind2].clone()
            })
            .collect();
        let ni_vec: Vec<Scalar<Secp256k1>> = (0..ttag - 1)
            .map(|j| {
                let ind1 = if j < i { j } else { j + 1 };
                let ind2 = if j < i { i - 1 } else { i };
                ni_vec_all[ind1][ind2].clone()
            })
            .collect();
        let delta = sign_keys_vec[i].phase2_delta_i(&alpha_vec_all[i], &beta_vec);
        let sigma = sign_keys_vec[i].phase2_sigma_i(&miu_vec_all[i], &ni_vec);
        delta_vec.push(delta);
        sigma_vec.push(sigma);
    }

    // Phase 3: 广播 delta_i 重构 delta^{-1}，计算 T_i 并验证
    let delta_inv = SignKeys::phase3_reconstruct_delta(&delta_vec);
    let mut t_vec = Vec::new();
    let mut l_vec = Vec::new();
    let mut t_proof_vec = Vec::new();
    for i in 0..ttag {
        let (t_i, l_i, t_proof_i) = SignKeys::phase3_compute_t_i(&sigma_vec[i]);
        t_vec.push(t_i);
        l_vec.push(l_i);
        t_proof_vec.push(t_proof_i);
    }
    for i in 0..ttag {
        PedersenProof::verify(&t_proof_vec[i]).map_err(|e| eyre::eyre!("T proof verify failed: {e:?}"))?;
    }

    // Phase 4: 解承诺 g^gamma_i 得到 R
    let r_vec: Vec<Point<Secp256k1>> = (0..ttag)
        .map(|i| {
            let m_b_gamma_vec = &m_b_gamma_vec_all[i];
            let b_proof_vec: Vec<&DLogProof<Secp256k1, Sha256>> = (0..ttag - 1)
                .map(|j| &m_b_gamma_vec[j].b_proof)
                .collect();
            SignKeys::phase4(&delta_inv, &b_proof_vec, decommit_vec1.clone(), &bc1_vec, i)
                .expect("phase4 R computation failed")
        })
        .collect();

    // Phase 5: 广播 R_dash 并验证 PDL 证明
    let r_dash_vec: Vec<Point<Secp256k1>> = (0..ttag)
        .map(|i| &r_vec[i] * &sign_keys_vec[i].k_i)
        .collect();
    let mut phase5_proofs_vec: Vec<Vec<_>> = vec![Vec::new(); ttag];
    for i in 0..ttag {
        for j in 0..ttag - 1 {
            let ind = if j < i { j } else { j + 1 };
            let proof = LocalSignature::phase5_proof_pdl(
                &r_dash_vec[i],
                &r_vec[i],
                &m_a_vec[i].0.c,
                &ek_vec[signer_indices[i]],
                &sign_keys_vec[i].k_i,
                &m_a_vec[i].1,
                &dlog_statement_vec[signer_indices[ind]],
            );
            phase5_proofs_vec[i].push(proof);
        }
    }
    for i in 0..ttag {
        LocalSignature::phase5_verify_pdl(
            &phase5_proofs_vec[i],
            &r_dash_vec[i],
            &r_vec[i],
            &m_a_vec[i].0.c,
            &ek_vec[signer_indices[i]],
            &dlog_statement_vec[..],
            signer_indices,
            i,
        )
        .map_err(|e| eyre::eyre!("phase5 PDL verify failed: {e:?}"))?;
    }
    LocalSignature::phase5_check_R_dash_sum(&r_dash_vec).map_err(|e| eyre::eyre!("phase5 R_dash sum check failed: {e:?}"))?;

    // Phase 6: 计算 S_i 与一致性证明并验证
    let mut s_vec_pts = Vec::new();
    let mut homo_elgamal_proof_vec = Vec::new();
    for i in 0..ttag {
        let (s_i, proof) = LocalSignature::phase6_compute_S_i_and_proof_of_consistency(
            &r_vec[i], &t_vec[i], &sigma_vec[i], &l_vec[i],
        );
        s_vec_pts.push(s_i);
        homo_elgamal_proof_vec.push(proof);
    }
    LocalSignature::phase6_verify_proof(&s_vec_pts, &homo_elgamal_proof_vec, &r_vec, &t_vec)
        .map_err(|e| eyre::eyre!("phase6 verify failed: {e:?}"))?;
    LocalSignature::phase6_check_S_i_sum(y, &s_vec_pts).map_err(|e| eyre::eyre!("phase6 S sum check failed: {e:?}"))?;

    // Phase 7: 各方计算 s_i 并聚合出最终签名
    let mut local_sig_vec = Vec::new();
    let mut s_shares = Vec::new();
    for i in 0..ttag {
        let local_sig = LocalSignature::phase7_local_sig(
            &sign_keys_vec[i].k_i,
            message_bn,
            &r_vec[i],
            &sigma_vec[i],
            y,
        );
        s_shares.push(local_sig.s_i.clone());
        local_sig_vec.push(local_sig);
    }
    let sig = local_sig_vec[0]
        .output_signature(&s_shares[1..])
        .map_err(|e| eyre::eyre!("phase7 signature aggregation failed: {e:?}"))?;

    // 库内验证签名正确性（verify 为 party_i 模块自由函数）
    verify(&sig, y, message_bn).map_err(|e| eyre::eyre!("GG20 signature verification failed: {e:?}"))?;

    let _ = g_w_vec; // g_w_vec 用于 phase6 的 blame 路径，正常路径不需要

    Ok(Gg20SignOutput {
        r_point: r_vec[0].clone(),
        partial_shares: s_shares,
        signature: sig,
    })
}

/// 用标准 secp256k1 验证签名 (r, s) 对消息哈希与公钥的正确性。
#[allow(dead_code)] // 公开 API：供外部独立验签（测试与 Java 侧校验路径使用）
pub fn verify_signature(
    r: &Scalar<Secp256k1>,
    s: &Scalar<Secp256k1>,
    message_bn: &curv::BigInt,
    pk: &Point<Secp256k1>,
) -> bool {
    use secp256k1::{Message, PublicKey, Signature, SECP256K1};

    let raw_msg = curv::BigInt::to_bytes(message_bn);
    let mut msg: Vec<u8> = vec![0u8; 32usize.saturating_sub(raw_msg.len())];
    msg.extend(raw_msg.iter());
    let msg = match Message::from_slice(msg.as_slice()) {
        Ok(m) => m,
        Err(_) => return false,
    };

    let slice = pk.to_bytes(false);
    let mut raw_pk = Vec::new();
    if slice.len() != 65 {
        raw_pk.insert(0, 4u8);
        raw_pk.extend(vec![0u8; 64 - slice.len()]);
        raw_pk.extend(slice.as_ref());
    } else {
        raw_pk.extend(slice.as_ref());
    }
    let pk = match PublicKey::from_slice(&raw_pk) {
        Ok(p) => p,
        Err(_) => return false,
    };

    let mut compact: Vec<u8> = Vec::new();
    let bytes_r = r.to_bytes();
    compact.extend(vec![0u8; 32 - bytes_r.len()]);
    compact.extend(bytes_r.iter());
    let bytes_s = s.to_bytes();
    compact.extend(vec![0u8; 32 - bytes_s.len()]);
    compact.extend(bytes_s.iter());
    let secp_sig = match Signature::from_compact(compact.as_slice()) {
        Ok(s) => s,
        Err(_) => return false,
    };

    SECP256K1.verify(&msg, &secp_sig, &pk).is_ok()
}

/// 将 32 字节消息哈希转为 BigInt（与 GG20 协议的消息编码一致）。
pub fn message_hash_to_bigint(hash: &[u8]) -> curv::BigInt {
    Sha256::new()
        .chain(hash)
        .result_bigint()
}

/// 将点编码为 33 字节压缩 SEC1 字节串（gRPC 传输用）。
pub fn encode_point(p: &Point<Secp256k1>) -> Vec<u8> {
    p.to_bytes(true).to_vec()
}

/// 将标量编码为 32 字节定长大端字节串（gRPC 传输用）。
pub fn encode_scalar(s: &Scalar<Secp256k1>) -> Vec<u8> {
    s.to_bytes().to_vec()
}

/// 将点编码为 hex 字符串（Java 侧 proto 契约为 hex string）。
pub fn hex_point(p: &Point<Secp256k1>) -> String {
    hex::encode(encode_point(p))
}

/// 将标量编码为 hex 字符串（Java 侧 proto 契约为 hex string）。
pub fn hex_scalar(s: &Scalar<Secp256k1>) -> String {
    hex::encode(encode_scalar(s))
}

/// 解码 hex 字符串为点。
pub fn point_from_hex(h: &str) -> eyre::Result<Point<Secp256k1>> {
    let bytes = hex::decode(h).map_err(|e| eyre::eyre!("invalid point hex: {e}"))?;
    Point::<Secp256k1>::from_bytes(&bytes).map_err(|e| eyre::eyre!("invalid point: {e:?}"))
}

/// 解码 hex 字符串为标量。
pub fn scalar_from_hex(h: &str) -> eyre::Result<Scalar<Secp256k1>> {
    let bytes = hex::decode(h).map_err(|e| eyre::eyre!("invalid scalar hex: {e}"))?;
    Scalar::<Secp256k1>::from_bytes(&bytes).map_err(|e| eyre::eyre!("invalid scalar: {e:?}"))
}

/// 可信协调器模型下缓存的单次 GG20 签名运行结果。
///
/// Sign RPC 在进程内完成全部签名方协议后缓存本结构；
/// Aggregate RPC 据此返回已验证的最终签名，并按请求方 party_index 分发部分份额。
#[derive(Clone)]
pub struct SignCache {
    pub r_point: Point<Secp256k1>,
    pub signature: SignatureRecid,
    /// 各签名方的部分签名份额 s_i，按签名方索引（0..=threshold）排列。
    pub partial_shares: Vec<Scalar<Secp256k1>>,
    /// 本次签名运行的消息哈希（hex），防止跨消息重放缓存。
    pub message_hash: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 端到端：真实 GG20 门限 ECDSA。
    ///
    /// t=1, n=3，签名方 [0,1]（ttag=2 > t=1）：
    ///   1. run_keygen 执行真实 Paillier + Feldman VSS + ZK 的 DKG
    ///   2. run_sign 执行真实 MtA + PDL + S 证明的 7 轮签名
    ///   3. verify_signature 用标准 secp256k1 验证签名（非 GG20 内部验证）
    ///
    /// 证明产出的签名可被**标准库**验证，即门限签名数学真实有效。
    ///
    /// 注：真实 Paillier 密钥生成（2048-bit 素数）较慢，此测试为集成级。
    #[test]
    fn gg20_end_to_end_real_threshold_ecdsa() {
        // === DKG：t=1, n=3 ===
        let (y_sum, x_shares, session) = run_keygen(1, 3)
            .expect("GG20 DKG failed");

        // DKG 输出完整性：3 方份额、聚合公钥非无穷远点
        assert_eq!(x_shares.len(), 3, "should produce n=3 secret shares");
        assert_eq!(session.shared_keys.len(), 3);
        assert!(!y_sum.is_zero(), "aggregate public key must not be infinity");

        // === Sign：对 32 字节消息哈希签名，签名方 [0,1] ===
        let message_hash: [u8; 32] = [
            0x4e, 0x65, 0x78, 0x75, 0x73, 0x43, 0x68, 0x61, 0x69, 0x6e, 0x2d, 0x4d, 0x50,
            0x43, 0x2d, 0x74, 0x65, 0x73, 0x74, 0x2d, 0x68, 0x61, 0x73, 0x68, 0x2d, 0x33,
            0x32, 0x62, 0x2d, 0x21, 0x00, 0x07,
        ];
        let message_bn = message_hash_to_bigint(&message_hash);

        let signer_indices = vec![0usize, 1usize]; // ttag=2 > t=1
        let output = run_sign(&session, &signer_indices, &message_bn)
            .expect("GG20 sign failed");

        // === 聚合签名 (r, s) 已由 run_sign 库内验证，此处用标准库复核 ===
        let sig = &output.signature;

        // === 关键断言：标准 secp256k1 验证通过 ===
        // verify_signature 使用独立的 secp256k1 库路径（非 GG20 内部 verify），
        // 证明门限协议产出的签名与单密钥 ECDSA 兼容。
        let r_point = &output.r_point;
        assert!(
            verify_signature(&sig.r, &sig.s, &message_bn, &y_sum),
            "GG20 threshold signature must verify under standard secp256k1"
        );
        assert!(!r_point.is_zero());

        // 负面用例：篡改消息后验证必须失败
        let tampered_bn = message_hash_to_bigint(&[0xAAu8; 32]);
        assert!(
            !verify_signature(&sig.r, &sig.s, &tampered_bn, &y_sum),
            "signature must NOT verify against a different message"
        );
    }
}

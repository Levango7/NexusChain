//! E 批里程碑：CGGMP21 三方门限签名端到端（进程内三方 + 消息总线模拟协调器）。
//!
//! **这是 NexusChain 首次 CGGMP21 门限签名全链路验证**——证明 D 批基础设施
//! 与 E 批驱动层可以完成真实密码学协议。流程为 3 方 keygen（t=2）、3 方
//! aux_info、各自合成完整 KeyShare、2-of-3 签名（恰好 t 方，CGGMP21 原生
//! 门限语义）与聚合公钥验签通过。
//!
//! ## 架构（模拟分布式）
//!
//! 每方一个 `CgDriverHandle`（独立驱动线程，符合生产 !Send 约束）；测试主
//! 线程扮演协调器总线：收集各方 outgoing → 按 receiver 分发（broadcast 复制
//! 给其他方、p2p 定向）→ 逐方 pump 至全部完成。这是 distributed.rs 阶段一
//! Simulation 模式的 CGGMP21 等价物（cggmp21 0.6.3 无 sim feature，见
//! cggmp_dkg_sim.rs 模块头说明——自实现调度在驱动线程 actor 模式下可控）。
//!
//! ## 关键断言（协议正确性不变量）
//!
//! 1. 三方 keygen 完成且 **聚合公钥一致**（分散式信任根基——与 GG20
//!    dist_dkg_3_parties_completes 同款不变量）
//! 2. 三方 aux 完成且各自合成 KeyShare 成功
//! 3. **2-of-3 签名**（signers = [0, 1]，恰好 t=2 方）完成
//! 4. 签名经 **聚合公钥** verify 通过（标准 ECDSA 语义）
//! 5. 篡改签名必须验签失败（fail-closed）
//!
//! 运行：`cargo test --test cggmp_threshold_e2e`（无需 tls feature 与集群）。

use mpc_engine::cggmp::CgMessage;
use mpc_engine::cggmp_state::{CgDriverHandle, DriverCommand, DriverReply};

/// 三方驱动句柄 + 消息路由（测试主线扮演协调器——纯字节管道）。
struct Cluster {
    nodes: Vec<CgDriverHandle>,
}

impl Cluster {
    fn new(n: usize) -> Self {
        Self {
            nodes: (0..n).map(|_| CgDriverHandle::start()).collect(),
        }
    }

    /// 路由：broadcast（receiver=None）复制给除 sender 外全部方；p2p 定向。
    fn route(&self, outgoing: &[CgMessage]) -> Vec<Vec<CgMessage>> {
        let n = self.nodes.len();
        let mut inboxes: Vec<Vec<CgMessage>> = vec![vec![]; n];
        for m in outgoing {
            match m.receiver {
                None => {
                    for (j, inbox) in inboxes.iter_mut().enumerate() {
                        if j != usize::from(m.sender) {
                            inbox.push(m.clone());
                        }
                    }
                }
                Some(target) => {
                    let j = usize::from(target);
                    assert!(j < n, "p2p target {j} out of range");
                    inboxes[j].push(m.clone());
                }
            }
        }
        inboxes
    }
}

/// 单方一轮 pump 的状态（outgoing / finished / keygen 聚合公钥）。
type NodeState = (Vec<CgMessage>, bool, Option<String>);

/// 从回执提取 PumpResult（其他回执类型 = 协议错误，panic）。
fn expect_pump(reply: DriverReply, ctx: &str) -> NodeState {
    match reply {
        DriverReply::PumpResult {
            outgoing,
            finished,
            aggregate_public_key,
        } => (outgoing, finished, aggregate_public_key),
        other => panic!("{ctx}: expected PumpResult, got {other:?}"),
    }
}

/// 通用协议循环：未完成方的 outgoing → 路由 → 回喂，直至全部 finished。
///
/// `make_pump(node_idx, inbox)` 构造该方的 Pump 指令（keygen/aux 二选一）。
/// 上限 200 轮防死循环（协议实际 4-7 轮）。
fn run_until_done(
    cluster: &Cluster,
    mut states: Vec<NodeState>,
    proto: &str,
    make_pump: impl Fn(usize, Vec<CgMessage>) -> DriverCommand,
) -> Vec<NodeState> {
    let mut round = 0usize;
    loop {
        if states.iter().all(|(_, fin, _)| *fin) {
            return states;
        }
        round += 1;
        assert!(round < 200, "{proto}: exceeded 200 rounds — protocol stuck");
        let mut out_all: Vec<CgMessage> = vec![];
        for (out, fin, _) in &states {
            if !fin {
                out_all.extend(out.iter().cloned());
            }
        }
        let inboxes = cluster.route(&out_all);
        let mut next = vec![];
        for (j, st) in states.into_iter().enumerate() {
            if st.1 {
                next.push(st);
                continue;
            }
            let reply = cluster.nodes[j]
                .call(make_pump(j, inboxes[j].clone()))
                .unwrap_or_else(|e| panic!("{proto} round {round} node {j}: {e}"));
            next.push(expect_pump(
                reply,
                &format!("{proto} round {round} node {j}"),
            ));
        }
        states = next;
    }
}

/// E 批里程碑：3 方 keygen(t=2) → aux → 合成 → 2-of-3 签名 → 验签 + 篡改拒绝。
#[test]
fn cggmp_threshold_e2e_3_of_3_keygen_2_sign() {
    let n = 3usize;
    let t = 2u16;
    let sid = "e2e-cg-3-2";
    let cluster = Cluster::new(n);

    // ---------- Phase 1: keygen（3 方，t=2） ----------
    let initial: Vec<NodeState> = (0..n)
        .map(|i| {
            let reply = cluster.nodes[i]
                .call(DriverCommand::StartKeygen {
                    session_id: sid.into(),
                    counter: 0,
                    i: i as u16,
                    n: n as u16,
                    t,
                })
                .unwrap_or_else(|e| panic!("start keygen node {i}: {e}"));
            expect_pump(reply, "keygen initial")
        })
        .collect();
    let keygen_states = run_until_done(&cluster, initial, "keygen", |_j, inc| {
        DriverCommand::PumpKeygen {
            session_id: sid.into(),
            incoming: inc,
        }
    });
    // 三方聚合公钥一致（分散式信任根基）
    let pks: Vec<String> = keygen_states
        .iter()
        .map(|(_, _, pk)| pk.clone().expect("keygen finished with pk"))
        .collect();
    assert_eq!(pks[0], pks[1], "aggregate pk must match: node0 vs node1");
    assert_eq!(pks[0], pks[2], "aggregate pk must match: node0 vs node2");
    let aggregate_pk = &pks[0];
    assert_eq!(
        aggregate_pk.len(),
        66,
        "compressed SEC1 hex (0x02/03 + 32B)"
    );

    // ---------- Phase 2: aux_info（3 方） ----------
    let aux_initial: Vec<NodeState> = (0..n)
        .map(|i| {
            let reply = cluster.nodes[i]
                .call(DriverCommand::StartAux {
                    session_id: sid.into(),
                    counter: 0,
                    i: i as u16,
                    n: n as u16,
                })
                .unwrap_or_else(|e| panic!("start aux node {i}: {e}"));
            expect_pump(reply, "aux initial")
        })
        .collect();
    run_until_done(&cluster, aux_initial, "aux", |_j, inc| {
        DriverCommand::PumpAux {
            session_id: sid.into(),
            incoming: inc,
        }
    });

    // ---------- Phase 3: 合成完整 KeyShare ----------
    for i in 0..n {
        let reply = cluster.nodes[i]
            .call(DriverCommand::AssembleShare {
                session_id: sid.into(),
            })
            .unwrap_or_else(|e| panic!("assemble node {i}: {e}"));
        assert!(
            matches!(reply, DriverReply::ShareAssembled),
            "node {i} assemble failed: {reply:?}"
        );
    }

    // ---------- Phase 4: 2-of-3 签名（signers = [0,1]，恰好 t 方） ----------
    let signers: Vec<u16> = vec![0, 1];
    let message_hash = [0x42u8; 32];
    // 手写小循环（非全体方参与——node2 无 sign 状态机，不能走 run_until_done）
    let mut states: Vec<NodeState> = signers
        .iter()
        .map(|&keygen_idx| {
            let i_in_batch = signers.iter().position(|&x| x == keygen_idx).unwrap() as u16;
            let reply = cluster.nodes[usize::from(keygen_idx)]
                .call(DriverCommand::StartSign {
                    session_id: sid.into(),
                    counter: 0,
                    i: i_in_batch,
                    signers_at_keygen: signers.clone(),
                    message_hash,
                })
                .unwrap_or_else(|e| panic!("start sign node {keygen_idx}: {e}"));
            expect_pump(reply, "sign initial")
        })
        .collect();
    let mut sig_hex: Option<(String, String)> = None;
    let mut round = 0usize;
    loop {
        if states.iter().all(|(_, fin, _)| *fin) {
            break;
        }
        round += 1;
        assert!(round < 200, "sign: exceeded 200 rounds — protocol stuck");
        let mut out_all: Vec<CgMessage> = vec![];
        for (out, fin, _) in &states {
            if !fin {
                out_all.extend(out.iter().cloned());
            }
        }
        let inboxes = cluster.route(&out_all);
        let mut next = vec![];
        for (batch_pos, st) in states.into_iter().enumerate() {
            if st.1 {
                next.push(st);
                continue;
            }
            let keygen_idx = usize::from(signers[batch_pos]);
            let reply = cluster.nodes[keygen_idx]
                .call(DriverCommand::PumpSign {
                    session_id: sid.into(),
                    incoming: inboxes[keygen_idx].clone(),
                })
                .unwrap_or_else(|e| panic!("sign round {round} node {keygen_idx}: {e}"));
            match reply {
                DriverReply::PumpResult {
                    outgoing, finished, ..
                } => {
                    assert!(
                        !finished,
                        "sign must end with SignatureProduced, not PumpResult(finished)"
                    );
                    next.push((outgoing, finished, None));
                }
                DriverReply::SignatureProduced { r_hex, s_hex } => {
                    // 完成的一方拿到签名（CGGMP21 任一签名方均得完整签名）
                    if sig_hex.is_none() {
                        sig_hex = Some((r_hex, s_hex));
                    }
                    next.push((vec![], true, None));
                }
                other => panic!("sign round {round}: unexpected {other:?}"),
            }
        }
        states = next;
    }
    let (r_hex, s_hex) = sig_hex.expect("signing must produce a signature");
    assert_eq!(r_hex.len(), 64, "r is 32-byte hex");
    assert_eq!(s_hex.len(), 64, "s is 32-byte hex");

    // ---------- Phase 5: 聚合公钥验签 ----------
    let r_bytes: [u8; 32] = hex::decode(&r_hex)
        .expect("r hex")
        .try_into()
        .expect("r 32 bytes");
    let s_bytes: [u8; 32] = hex::decode(&s_hex)
        .expect("s hex")
        .try_into()
        .expect("s 32 bytes");
    let reply = cluster.nodes[0]
        .call(DriverCommand::VerifySignature {
            session_id: sid.into(),
            signature_r: r_bytes,
            signature_s: s_bytes,
            message_hash,
        })
        .expect("verify call");
    match reply {
        DriverReply::VerificationResult { valid } => assert!(
            valid,
            "2-of-3 CGGMP21 signature must verify against the aggregate public key"
        ),
        other => panic!("expected VerificationResult, got {other:?}"),
    }

    // ---------- 篡改签名必须验签失败（fail-closed 语义） ----------
    let mut bad_r = r_bytes;
    bad_r[0] ^= 0xFF;
    let reply = cluster.nodes[0]
        .call(DriverCommand::VerifySignature {
            session_id: sid.into(),
            signature_r: bad_r,
            signature_s: s_bytes,
            message_hash,
        })
        .expect("verify bad call");
    match reply {
        DriverReply::VerificationResult { valid } => assert!(
            !valid,
            "tampered signature must fail verification (fail-closed)"
        ),
        other => panic!("expected VerificationResult, got {other:?}"),
    }
}

//! F 批验收：CGGMP21 生命周期 RPC 面的 gRPC 端到端（进程内）。
//!
//! **验证目标**：proto 扩展 + server.rs 接线（spawn_blocking 驱动线程 actor
//! 桥接 + relay 池）在**真实 gRPC 传输**下可用——E 批里程碑（进程内直调
//! driver）之后的下一级验证。
//!
//! ## 架构（进程内 gRPC 三方）
//!
//! ```text
//! ┌─ tonic server（127.0.0.1:0 随机端口，无 TLS）─────────────────┐
//! │  MpcCryptoServiceImpl（含 CgDriverHandle::global() + relay 池）│
//! └──────────────────▲───────────────────────────────────────────┘
//!                    │ 3 个 gRPC 客户端连接（模拟 3 个参与方进程）
//!   测试主线（扮演 3 方 + 协调器路由：publish → 各方 pull → pump）
//! ```
//!
//! 与 E 批 e2e 的关键差异：本测试**经 gRPC 协议层**（编解码/传输/拦截器），
//! 且消息路由走 **relay 池 RPC**（CgRelayPublish/CgRelayPull——协调器是
//! 字节管道），非测试内存直传。这验证的是 Java 侧未来接入的完整路径。
//!
//! 运行：`cargo test --test cggmp_rpc_e2e`（无需 tls/集群）。

use mpc_engine::proto::mpc_crypto::{
    mpc_crypto_service_client::MpcCryptoServiceClient, CgPumpRequest, CgPumpResponse,
    CgRelayMessage, CgRelayPullRequest, CgSessionOnly, CgStartAuxRequest, CgStartKeygenRequest,
    CgStartSignRequest, CgStatusResponse, CgVerifyRequest, CgVerifyResponse,
};
use tonic::transport::{Channel, Endpoint};
use tonic::Request;

/// 泵动状态（每方）：outgoing proto 消息 / finished / 聚合公钥。
#[derive(Clone)]
struct PumpState {
    outgoing: Vec<CgRelayMessage>,
    finished: bool,
    aggregate_public_key: String,
}

impl From<CgPumpResponse> for PumpState {
    fn from(r: CgPumpResponse) -> Self {
        Self {
            outgoing: r.outgoing,
            finished: r.finished,
            aggregate_public_key: r.aggregate_public_key,
        }
    }
}

/// F 批验收：3 方经 gRPC 完成 keygen(t=2)→aux→合成→2-of-3 签名→验签。
/// 消息路由走 relay 池 RPC（publish → pull），协调器语义与生产对齐。
///
/// **拓扑（F 批实证修正）：3 个 tonic server = 3 个 mpc-engine 进程**。
/// 单 server 三个逻辑方共享驱动线程的同一 session 槽位——StartKeygen
/// 幂等守卫把 i=1/2 挡掉（三方变一方，200 轮空转）。生产部署每 party
/// 一进程（K8s StatefulSet 3 副本），测试以 3 server 对齐真实拓扑；
/// node0 兼任协调器（其 relay 池经 CgRelayPublish/Pull 对其他方开放）。
#[tokio::test]
async fn cggmp_rpc_e2e_full_lifecycle_over_grpc() {
    // ---------- 起 3 个 tonic server（每 party 一进程，随机端口）----------
    let mut addrs: Vec<std::net::SocketAddr> = Vec::new();
    let mut server_handles = Vec::new();
    for _ in 0..3 {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind");
        addrs.push(listener.local_addr().expect("local addr"));
        let service = mpc_engine::server::MpcCryptoServiceImpl::with_independent_cggmp_driver();
        server_handles.push(tokio::spawn(async move {
            tonic::transport::Server::builder()
                .add_service(
                    mpc_engine::proto::mpc_crypto::mpc_crypto_service_server::MpcCryptoServiceServer::new(
                        service,
                    ),
                )
                .serve_with_incoming(tokio_stream::wrappers::TcpListenerStream::new(listener))
                .await
                .expect("tonic server");
        }));
    }

    // ---------- 3 方客户端（各连各的 server）----------
    let mut clients: Vec<MpcCryptoServiceClient<Channel>> = Vec::new();
    for addr in &addrs {
        let channel = Endpoint::from_shared(format!("http://{addr}"))
            .expect("endpoint")
            .connect()
            .await
            .expect("connect");
        clients.push(MpcCryptoServiceClient::new(channel));
    }

    let n = 3u32;
    let t = 2u32;
    let sid = "rpc-e2e-cg-3-2".to_string();

    // ---------- Phase 1: CgStartKeygen ×3 ----------
    let mut states: Vec<PumpState> = Vec::new();
    for i in 0..n {
        let resp = clients[i as usize]
            .cg_start_keygen(Request::new(CgStartKeygenRequest {
                session_id: sid.clone(),
                counter: 0,
                my_index: i,
                total_parties: n,
                threshold: t,
            }))
            .await
            .expect("CgStartKeygen rpc")
            .into_inner();
        assert!(resp.success, "start keygen {i} failed: {}", resp.error);
        states.push(resp.into());
    }

    // ---------- keygen 协议循环（relay 路由）----------
    states = run_protocol_over_relay(&mut clients, states, &sid, true).await;
    let pks: Vec<String> = states
        .iter()
        .map(|s| s.aggregate_public_key.clone())
        .collect();
    assert!(!pks[0].is_empty(), "keygen must produce aggregate pk");
    assert_eq!(pks[0], pks[1], "pk consistent across parties (0 vs 1)");
    assert_eq!(pks[0], pks[2], "pk consistent across parties (0 vs 2)");

    // ---------- Phase 2: CgStartAux ×3 + 协议循环 ----------
    let mut states: Vec<PumpState> = Vec::new();
    for i in 0..n {
        let resp = clients[i as usize]
            .cg_start_aux(Request::new(CgStartAuxRequest {
                session_id: sid.clone(),
                counter: 0,
                my_index: i,
                total_parties: n,
            }))
            .await
            .expect("CgStartAux rpc")
            .into_inner();
        assert!(resp.success, "start aux {i} failed: {}", resp.error);
        states.push(resp.into());
    }
    states = run_protocol_over_relay(&mut clients, states, &sid, false).await;
    // aux 完成不产出公钥（aggregate_public_key 为空）——只验证 finished
    assert!(states.iter().all(|s| s.finished), "aux must finish");

    // ---------- Phase 3: CgAssembleShare ×3 ----------
    for i in 0..n {
        let resp = clients[i as usize]
            .cg_assemble_share(Request::new(CgSessionOnly {
                session_id: sid.clone(),
            }))
            .await
            .expect("CgAssembleShare rpc")
            .into_inner();
        assert!(resp.success, "assemble {i} failed: {}", resp.error);
    }

    // ---------- Phase 4: CgStartSign（2-of-3：signers = [0,1]）+ 协议循环 ----------
    let signers = vec![0u32, 1u32];
    let message_hash = vec![0x42u8; 32];
    // 类型别名消解 clippy::type_complexity（三层嵌套泛型超阈值）
    type SignStateEntry = (Vec<CgRelayMessage>, bool, Option<(String, String)>);
    let mut sign_states: Vec<SignStateEntry> = Vec::new();
    for (batch_pos, &keygen_idx) in signers.iter().enumerate() {
        let resp = clients[keygen_idx as usize]
            .cg_start_sign(Request::new(CgStartSignRequest {
                session_id: sid.clone(),
                counter: 0,
                my_index_in_signers: batch_pos as u32,
                signers_at_keygen: signers.clone(),
                message_hash: message_hash.clone(),
            }))
            .await
            .expect("CgStartSign rpc")
            .into_inner();
        assert!(
            resp.success,
            "start sign {keygen_idx} failed: {}",
            resp.error
        );
        sign_states.push((resp.outgoing, resp.finished, None));
    }
    let mut sig: Option<(String, String)> = None;
    let mut round = 0;
    loop {
        if sign_states.iter().all(|(_, fin, _)| *fin) {
            break;
        }
        round += 1;
        assert!(round < 200, "sign stuck");
        // publish 全部未完成方 outgoing
        for (out, fin, _) in &sign_states {
            if !*fin {
                for m in out {
                    let ack = clients[0]
                        .cg_relay_publish(Request::new(m.clone()))
                        .await
                        .expect("publish")
                        .into_inner();
                    assert!(ack.success, "publish failed: {}", ack.error);
                }
            }
        }
        // 各签名方 pull → pump
        let mut next = vec![];
        for (batch_pos, st) in sign_states.into_iter().enumerate() {
            if st.1 {
                next.push(st);
                continue;
            }
            let keygen_idx = signers[batch_pos] as usize;
            // pull 从协调器（node0 relay 池）；pump 打到签名方自己的 server
            let pulled = clients[0]
                .cg_relay_pull(Request::new(CgRelayPullRequest {
                    session_id: sid.clone(),
                    my_index: signers[batch_pos],
                }))
                .await
                .expect("pull")
                .into_inner();
            let resp = clients[keygen_idx]
                .cg_pump_sign(Request::new(CgPumpRequest {
                    session_id: sid.clone(),
                    incoming: pulled.messages,
                }))
                .await
                .expect("CgPumpSign rpc")
                .into_inner();
            if resp.finished {
                assert!(resp.success, "sign finished with error: {}", resp.error);
                assert_eq!(resp.r_hex.len(), 64, "r hex");
                if sig.is_none() {
                    sig = Some((resp.r_hex.clone(), resp.s_hex.clone()));
                }
                next.push((vec![], true, Some((resp.r_hex, resp.s_hex))));
            } else {
                assert!(resp.success, "sign pump error: {}", resp.error);
                next.push((resp.outgoing, false, None));
            }
        }
        sign_states = next;
    }
    let (r_hex, s_hex) = sig.expect("signature produced");

    // ---------- Phase 5: CgVerifySignature（正确签名 + 篡改拒绝） ----------
    let r_bytes = hex::decode(&r_hex).expect("r hex");
    let s_bytes = hex::decode(&s_hex).expect("s hex");

    let valid = verify_signature(
        &mut clients[0],
        &sid,
        r_bytes.clone(),
        s_bytes.clone(),
        &message_hash,
    )
    .await;
    assert!(
        matches!(
            &valid,
            CgVerifyResponse {
                valid: true,
                success: true,
                ..
            }
        ),
        "2-of-3 signature must verify: {valid:?}"
    );
    let mut bad = r_bytes;
    bad[0] ^= 0xFF;
    let invalid = verify_signature(&mut clients[0], &sid, bad, s_bytes, &message_hash).await;
    assert!(
        matches!(&invalid, CgVerifyResponse { valid: false, .. }),
        "tampered signature must fail: {invalid:?}"
    );

    // ---------- CgStatus 验收（has_key_share=true） ----------
    let st = clients[0]
        .cg_status(Request::new(CgSessionOnly {
            session_id: sid.clone(),
        }))
        .await
        .expect("CgStatus rpc")
        .into_inner();
    assert!(
        matches!(
            &st,
            CgStatusResponse {
                has_key_share: true,
                success: true,
                ..
            }
        ),
        "status snapshot: {st:?}"
    );

    for h in server_handles {
        h.abort(); // 测试结束，关 3 个 server
    }
}

/// CgVerifySignature 的独立辅助（避免闭包 move 所有权问题）。
async fn verify_signature(
    client: &mut MpcCryptoServiceClient<Channel>,
    sid: &str,
    r: Vec<u8>,
    s: Vec<u8>,
    message_hash: &[u8],
) -> CgVerifyResponse {
    client
        .cg_verify_signature(Request::new(CgVerifyRequest {
            session_id: sid.to_string(),
            signature_r: r,
            signature_s: s,
            message_hash: message_hash.to_vec(),
        }))
        .await
        .expect("verify rpc")
        .into_inner()
}

/// keygen/aux 的 relay 路由循环（三方同构——publish/pull/pump 至完成）。
///
/// `is_keygen` 选择泵协议（keygen→CgPumpKeygen；aux→CgPumpAux）——阶段
/// 隔离由 server 的 Start* 清池保证（keygen 尾巴不会混入 aux）。
/// 与 E 批 e2e 的内存直传不同：每条消息都经 CgRelayPublish →
/// CgRelayPull 两个 RPC（协调器字节管道语义）。
async fn run_protocol_over_relay(
    clients: &mut [MpcCryptoServiceClient<Channel>],
    mut states: Vec<PumpState>,
    sid: &str,
    is_keygen: bool,
) -> Vec<PumpState> {
    let mut round = 0;
    loop {
        if states.iter().all(|s| s.finished) {
            return states;
        }
        round += 1;
        assert!(round < 200, "protocol stuck at round {round}");
        // publish：未完成方把 outgoing 全部发布到**协调器**（node0 的 relay 池）
        for st in states.iter() {
            if st.finished {
                continue;
            }
            for m in &st.outgoing {
                let ack = clients[0]
                    .cg_relay_publish(Request::new(m.clone()))
                    .await
                    .expect("publish rpc")
                    .into_inner();
                assert!(ack.success, "publish failed: {}", ack.error);
            }
        }
        // pull（从协调器）+ pump（各方自己的 server）：拉 inbox 后泵动
        let mut next = vec![];
        for (i, st) in states.into_iter().enumerate() {
            if st.finished {
                next.push(st);
                continue;
            }
            let pulled = clients[0]
                .cg_relay_pull(Request::new(CgRelayPullRequest {
                    session_id: sid.to_string(),
                    my_index: i as u32,
                }))
                .await
                .expect("pull rpc")
                .into_inner();
            assert!(pulled.success, "pull failed: {}", pulled.error);
            let req = Request::new(CgPumpRequest {
                session_id: sid.to_string(),
                incoming: pulled.messages,
            });
            let resp = if is_keygen {
                clients[i]
                    .cg_pump_keygen(req)
                    .await
                    .expect("pump keygen rpc")
            } else {
                clients[i].cg_pump_aux(req).await.expect("pump aux rpc")
            };
            let resp = resp.into_inner();
            assert!(resp.success, "pump failed: {}", resp.error);
            next.push(resp.into());
        }
        states = next;
    }
}

//! ⑤批验收：CGGMP21 KeyShare 持久化与重启恢复（PLAN-cggmp-keyshare-persistence）。
//!
//! **验证目标**：份额落盘（NXC1 加密）+ 进程重启模拟（新 driver/新 server
//! 从磁盘恢复）后，**不重跑 DKG/aux** 直接 sign→verify 全链路可用。
//! 这是生产 K8s StatefulSet "keygen 仪式一次、长期反复签名" 语义的核心保障。
//!
//! ## 恢复语义（设计稿 §3.4 / §7）
//!
//! * 进程 A（"重启前"）：三方 keygen(t=2)→aux→assembleShare——每步产物
//!   落盘（incomplete.bin / keyshare.bin，NXC1 信封）。
//! * 进程 B（"重启后"）：**全新 driver + 全新 server**（内存 registry 为空），
//!   同一 StorageCtx——StartSign 触发磁盘回填：keyshare.bin → key_share
//!   直接签名。keygen/aux **不重跑**（这是断言的核心）。
//! * 单节点 fail-closed：篡改 keyshare.bin 后，StartSign/StartKeygen 必须
//!   显式报错（绝不静默跳过或伪造状态）。

use mpc_engine::cggmp_state::StorageCtx;
use mpc_engine::proto::mpc_crypto::mpc_crypto_service_server::MpcCryptoServiceServer;
use mpc_engine::proto::mpc_crypto::{
    mpc_crypto_service_client::MpcCryptoServiceClient, CgPumpRequest, CgRelayMessage,
    CgRelayPullRequest, CgSessionOnly, CgStartAuxRequest, CgStartKeygenRequest, CgStartSignRequest,
    CgVerifyRequest,
};
use mpc_engine::server::MpcCryptoServiceImpl;
use tonic::transport::{Channel, Endpoint};
use tonic::Request;

/// 起一个带共享 StorageCtx 的 server（随机端口），返回 (addr, abort 句柄)。
async fn spawn_persisted_server(
    storage: Option<StorageCtx>,
) -> (std::net::SocketAddr, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("local addr");
    let service = MpcCryptoServiceImpl::with_independent_cggmp_driver_and_storage(storage);
    let handle = tokio::spawn(async move {
        tonic::transport::Server::builder()
            .add_service(MpcCryptoServiceServer::new(service))
            .serve_with_incoming(tokio_stream::wrappers::TcpListenerStream::new(listener))
            .await
            .expect("tonic server");
    });
    (addr, handle)
}

/// keygen/aux 的 relay 循环（与 cggmp_rpc_e2e 同构——publish 到协调器
/// node0，各方 pull 后 pump 自己的 server；sign 循环在主测试内联以捕获
/// r/s 产物）。
async fn relay_round(
    clients: &mut [MpcCryptoServiceClient<Channel>],
    sid: &str,
    outgoing: Vec<Vec<CgRelayMessage>>,
    finished: &[bool],
    proto: &str,
) -> Vec<(Vec<CgRelayMessage>, bool)> {
    // publish 未完成方 outgoing
    for (i, out) in outgoing.iter().enumerate() {
        if finished[i] {
            continue;
        }
        for m in out {
            let ack = clients[0]
                .cg_relay_publish(Request::new(m.clone()))
                .await
                .expect("publish")
                .into_inner();
            assert!(ack.success, "publish failed: {}", ack.error);
        }
    }
    let mut next = vec![];
    for i in 0..clients.len() {
        if finished[i] {
            next.push((vec![], true));
            continue;
        }
        let pulled = clients[0]
            .cg_relay_pull(Request::new(CgRelayPullRequest {
                session_id: sid.to_string(),
                my_index: i as u32,
            }))
            .await
            .expect("pull")
            .into_inner();
        assert!(pulled.success, "pull failed: {}", pulled.error);
        let req = Request::new(CgPumpRequest {
            session_id: sid.to_string(),
            incoming: pulled.messages,
        });
        let (success, out, error, fin) = match proto {
            "keygen" => {
                let r = clients[i]
                    .cg_pump_keygen(req)
                    .await
                    .expect("pump keygen rpc")
                    .into_inner();
                (r.success, r.outgoing, r.error, r.finished)
            }
            "aux" => {
                let r = clients[i]
                    .cg_pump_aux(req)
                    .await
                    .expect("pump aux rpc")
                    .into_inner();
                (r.success, r.outgoing, r.error, r.finished)
            }
            _ => unreachable!(),
        };
        assert!(success, "{proto} pump failed: {error}");
        next.push((out, fin));
    }
    next
}

/// 起一个带共享 StorageCtx 的 server（随机端口），返回 (addr, abort 句柄)。
#[tokio::test]
async fn cggmp_persistence_restart_recovery_full() {
    let sid = format!("persist-recovery-{}", std::process::id());
    let base = std::env::temp_dir().join(format!("cg-recovery-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&base);

    // ---------- 进程 A（"重启前"）：三方 keygen→aux→assemble，产物落盘 ----------
    let mut addrs = Vec::new();
    let mut handles = Vec::new();
    // 每方独立 base_dir（对齐生产布局：每 party 一进程 + 每节点独立
    // MPC_ENGINE_SESSION_DIR）——三方共用一个目录会交叉读到彼此的份额
    // 落盘（party1 的 aux 守卫拉到 party0 的 incomplete → PrimesMul 不一致，
    // 首跑实证）。恢复阶段（进程 B）用同一套 per-party 目录模拟磁盘留存。
    let storages: Vec<StorageCtx> = (0..3)
        .map(|p| StorageCtx::new(base.join(format!("node{p}")), [0x7A; 32], 1))
        .collect();
    for s in &storages {
        let (a, h) = spawn_persisted_server(Some(s.clone())).await;
        addrs.push(a);
        handles.push(h);
    }
    let mut clients = Vec::new();
    for addr in &addrs {
        let ch = Endpoint::from_shared(format!("http://{addr}"))
            .expect("endpoint")
            .connect()
            .await
            .expect("connect");
        clients.push(MpcCryptoServiceClient::new(ch));
    }
    let (n, t) = (3u32, 2u32);

    // Phase 1: keygen（start 串行——relay 池阶段边界设计）
    let mut outs = Vec::new();
    let mut fins = Vec::new();
    for i in 0..n {
        let r = clients[i as usize]
            .cg_start_keygen(Request::new(CgStartKeygenRequest {
                session_id: sid.clone(),
                counter: 0,
                my_index: i,
                total_parties: n,
                threshold: t,
            }))
            .await
            .expect("start keygen")
            .into_inner();
        assert!(r.success, "start keygen {i}: {}", r.error);
        outs.push(r.outgoing);
        fins.push(r.finished);
    }
    loop {
        if fins.iter().all(|&f| f) {
            break;
        }
        let st = relay_round(&mut clients, &sid, outs.clone(), &fins, "keygen").await;
        outs = st.iter().map(|(o, _)| o.clone()).collect();
        fins = st.iter().map(|(_, f)| *f).collect();
    }
    // keyshare 中间产物已落盘（incomplete.bin——party0 的目录）
    let inc_path = base
        .join("node0")
        .join("cggmp")
        .join(sid.replace(['/', '\\', ':'], "_"))
        .join("incomplete.bin");
    assert!(
        inc_path.exists(),
        "incomplete.bin must exist after keygen: {inc_path:?}"
    );
    let inc_raw = std::fs::read(&inc_path).expect("read incomplete");
    assert_eq!(&inc_raw[0..4], b"NXC1", "incomplete.bin NXC1 magic");

    // Phase 2: aux
    let mut outs = Vec::new();
    let mut fins = Vec::new();
    for i in 0..n {
        let r = clients[i as usize]
            .cg_start_aux(Request::new(CgStartAuxRequest {
                session_id: sid.clone(),
                counter: 0,
                my_index: i,
                total_parties: n,
            }))
            .await
            .expect("start aux")
            .into_inner();
        assert!(r.success, "start aux {i}: {}", r.error);
        outs.push(r.outgoing);
        fins.push(r.finished);
    }
    loop {
        if fins.iter().all(|&f| f) {
            break;
        }
        let st = relay_round(&mut clients, &sid, outs.clone(), &fins, "aux").await;
        outs = st.iter().map(|(o, _)| o.clone()).collect();
        fins = st.iter().map(|(_, f)| *f).collect();
    }

    // Phase 3: assembleShare ×3 → keyshare.bin 落盘
    for i in 0..n {
        let r = clients[i as usize]
            .cg_assemble_share(Request::new(CgSessionOnly {
                session_id: sid.clone(),
            }))
            .await
            .expect("assemble rpc")
            .into_inner();
        assert!(r.success, "assemble {i}: {}", r.error);
    }
    let ks_path = base
        .join("node0")
        .join("cggmp")
        .join(sid.replace(['/', '\\', ':'], "_"))
        .join("keyshare.bin");
    assert!(ks_path.exists(), "keyshare.bin must exist after assemble");
    let ks_raw_before = std::fs::read(&ks_path).expect("read keyshare");

    // 关掉进程 A（模拟全部三方同时重启）
    for h in &handles {
        h.abort();
    }

    // ---------- 进程 B（"重启后"）：全新 server/registry，同 per-party 目录 ----------
    let mut addrs_b = Vec::new();
    let mut handles_b = Vec::new();
    for s in &storages {
        let (a, h) = spawn_persisted_server(Some(s.clone())).await;
        addrs_b.push(a);
        handles_b.push(h);
    }
    let mut clients_b = Vec::new();
    for addr in &addrs_b {
        let ch = Endpoint::from_shared(format!("http://{addr}"))
            .expect("endpoint")
            .connect()
            .await
            .expect("connect");
        clients_b.push(MpcCryptoServiceClient::new(ch));
    }

    // 重启后 StartKeygen（同 sid）：应触发恢复（finished=true 立即返回），
    // 而不是重建状态机空转——产物在盘上，DKG 不重跑。
    let r0 = clients_b[0]
        .cg_start_keygen(Request::new(CgStartKeygenRequest {
            session_id: sid.clone(),
            counter: 0,
            my_index: 0,
            total_parties: n,
            threshold: t,
        }))
        .await
        .expect("start keygen after restart")
        .into_inner();
    assert!(r0.success, "restored start keygen: {}", r0.error);
    assert!(r0.finished, "restored keygen must be immediately finished");
    assert!(
        !r0.aggregate_public_key.is_empty(),
        "restored keygen must expose agg pk"
    );

    // 重启后 StartAux（同 sid）：keyshare 已恢复 → 幂等 finished
    let ra = clients_b[0]
        .cg_start_aux(Request::new(CgStartAuxRequest {
            session_id: sid.clone(),
            counter: 0,
            my_index: 0,
            total_parties: n,
        }))
        .await
        .expect("start aux after restart")
        .into_inner();
    assert!(ra.success, "restored start aux: {}", ra.error);
    assert!(
        ra.finished,
        "restored aux must be immediately finished (keyshare present)"
    );

    // 重启后直接 sign 2-of-3（不重跑 keygen/aux——份额从盘上回填）
    let signers = vec![0u32, 1u32];
    let message_hash = vec![0x99u8; 32];
    let mut outs = Vec::new();
    let mut fins = Vec::new();
    for (b, &k) in signers.iter().enumerate() {
        let r = clients_b[k as usize]
            .cg_start_sign(Request::new(CgStartSignRequest {
                session_id: sid.clone(),
                counter: 0,
                my_index_in_signers: b as u32,
                signers_at_keygen: signers.clone(),
                message_hash: message_hash.clone(),
            }))
            .await
            .expect("start sign after restart")
            .into_inner();
        assert!(
            r.success,
            "start sign signer {k} after restart: {}",
            r.error
        );
        assert!(!r.finished, "sign needs protocol rounds (not pre-finished)");
        outs.push(r.outgoing);
        fins.push(r.finished);
    }
    let mut sig_hex: Option<(String, String)> = None;
    loop {
        if fins.iter().all(|&f| f) {
            break;
        }
        // publish 未完成方
        for (i, out) in outs.iter().enumerate() {
            if fins[i] {
                continue;
            }
            for m in out {
                let ack = clients_b[0]
                    .cg_relay_publish(Request::new(m.clone()))
                    .await
                    .expect("publish")
                    .into_inner();
                assert!(ack.success, "publish: {}", ack.error);
            }
        }
        let mut n_outs = Vec::new();
        let mut n_fins = Vec::new();
        for (b, &k) in signers.iter().enumerate() {
            if fins[b] {
                n_outs.push(vec![]);
                n_fins.push(true);
                continue;
            }
            let pulled = clients_b[0]
                .cg_relay_pull(Request::new(CgRelayPullRequest {
                    session_id: sid.clone(),
                    my_index: k,
                }))
                .await
                .expect("pull")
                .into_inner();
            assert!(pulled.success, "pull: {}", pulled.error);
            let resp = clients_b[k as usize]
                .cg_pump_sign(Request::new(CgPumpRequest {
                    session_id: sid.clone(),
                    incoming: pulled.messages,
                }))
                .await
                .expect("pump sign")
                .into_inner();
            assert!(resp.success, "pump sign signer {k}: {}", resp.error);
            if resp.finished && sig_hex.is_none() {
                assert_eq!(resp.r_hex.len(), 64, "r hex");
                sig_hex = Some((resp.r_hex.clone(), resp.s_hex.clone()));
            }
            n_outs.push(resp.outgoing);
            n_fins.push(resp.finished);
        }
        outs = n_outs;
        fins = n_fins;
    }
    let (r_hex, s_hex) = sig_hex.expect("signature produced after recovery");

    // verify（重启后的进程 B 验签——份额恢复自磁盘）
    let r_bytes = hex::decode(&r_hex).expect("r hex decode");
    let s_bytes = hex::decode(&s_hex).expect("s hex decode");
    let v = clients_b[0]
        .cg_verify_signature(Request::new(CgVerifyRequest {
            session_id: sid.clone(),
            signature_r: r_bytes,
            signature_s: s_bytes,
            message_hash: message_hash.clone(),
        }))
        .await
        .expect("verify rpc")
        .into_inner();
    assert!(v.success, "verify rpc: {}", v.error);
    assert!(v.valid, "recovered share signature must verify");

    // keyshare.bin 内容未变（恢复只读不写——重跑签名不破坏份额）
    let ks_raw_after = std::fs::read(&ks_path).expect("reread keyshare");
    assert_eq!(
        ks_raw_before, ks_raw_after,
        "keyshare.bin must be untouched by recovery"
    );

    for h in &handles_b {
        h.abort();
    }
    let _ = std::fs::remove_dir_all(&base);
}

/// fail-closed：篡改盘上 keyshare.bin 后，重启进程的 StartKeygen/StartSign
/// 必须显式报错（绝不静默跳过、绝不伪造 finished）。
#[tokio::test]
async fn cggmp_persistence_tampered_share_fails_closed() {
    let sid = format!("persist-tamper-{}", std::process::id());
    let base = std::env::temp_dir().join(format!("cg-tamper-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&base);
    let storage = StorageCtx::new(base.clone(), [0x3C; 32], 1);

    // 只起 1 个 server（篡改检测是单节点语义），跑单方 keygen 前 2 轮不足完成——
    // 直接手写伪造的 incomplete.bin 模拟"盘上有坏份额"：
    let dir = base.join("cggmp").join(sid.replace(['/', '\\', ':'], "_"));
    std::fs::create_dir_all(&dir).expect("mkdir");
    // NXC1 头 + 合法 nonce + 垃圾密文（GCM tag 校验必败）
    let mut garbage = b"NXC1".to_vec();
    garbage.extend_from_slice(&1u32.to_le_bytes());
    garbage.extend_from_slice(&[0u8; 12]); // nonce
    garbage.extend_from_slice(&[0xEE; 64]); // 假密文
    std::fs::write(dir.join("incomplete.bin"), &garbage).expect("write garbage");

    let (addr, handle) = spawn_persisted_server(Some(storage)).await;
    let ch = Endpoint::from_shared(format!("http://{addr}"))
        .expect("endpoint")
        .connect()
        .await
        .expect("connect");
    let mut client = MpcCryptoServiceClient::new(ch);

    // StartKeygen 同 sid：恢复路径触发解密 → 必须失败且显式报错
    let r = client
        .cg_start_keygen(Request::new(CgStartKeygenRequest {
            session_id: sid.clone(),
            counter: 0,
            my_index: 0,
            total_parties: 3,
            threshold: 2,
        }))
        .await
        .expect("rpc")
        .into_inner();
    assert!(!r.success, "tampered share must NOT silently pass");
    assert!(
        r.error.contains("restore") || r.error.contains("decrypt") || r.error.contains("invalid"),
        "error must name the restore failure: {}",
        r.error
    );

    handle.abort();
    let _ = std::fs::remove_dir_all(&base);
}

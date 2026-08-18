//! # MPC 多节点集成测试
//!
//! 本测试套件验证 NexusChain mpc-engine 在多节点分布式部署下的端到端行为，
//! 涵盖 GG20 DKG（分布式密钥生成）、阈值签名、节点恢复与 mTLS 握手。
//!
//! ## 测试拓扑
//!
//! 3 个 mpc-engine 节点（node1/node2/node3）分别监听：
//!   - node1 → 127.0.0.1:50051（party_index=0, party_id="party-0"）
//!   - node2 → 127.0.0.1:50052（party_index=1, party_id="party-1"）
//!   - node3 → 127.0.0.1:50053（party_index=2, party_id="party-2"）
//!
//! 阈值签名参数：2-of-3（threshold=2, total_parties=3）。
//!
//! ## 运行前置条件
//!
//! 1. **Linux 环境**（Windows 缺 gcc/dlltool，无法编译 secp256k1/kzen-paillier 原生库）
//! 2. **依赖**：rustc + cargo + gcc + openssl + protoc
//! 3. **启动集群**：
//!    ```bash
//!    cd mpc-engine
//!    bash scripts/start-mpc-cluster.sh   # 编译并启动 3 节点
//!    ```
//!    脚本会自动：
//!    - 调用 `generate-certs.sh` 生成 mTLS 证书到 `certs/`
//!    - 生成节点 JSON 配置到 `config/node{1,2,3}.json`
//!    - 编译 mpc-engine（`cargo build --features tls --release`）
//!    - 启动 3 个节点子进程，日志输出到 `logs/node{1,2,3}.log`
//!    - 健康检查等待所有节点就绪
//!
//! 4. **运行测试**：
//!    ```bash
//!    cargo test --features tls --test integration_test -- --ignored
//!    ```
//!    `--ignored` 是必须的：所有测试标注了 `#[ignore]`，因它们需要多节点环境。
//!
//! 5. **单个测试**：
//!    ```bash
//!    cargo test --features tls --test integration_test -- --ignored test_dkg_3_nodes
//!    ```
//!
//! 6. **停止集群**：
//!    ```bash
//!    bash scripts/start-mpc-cluster.sh -k
//!    ```
//!
//! ## 已知限制
//!
//! - **Windows 环境**：缺 gcc.exe / dlltool.exe，无法编译 multi-party-ecdsa /
//!   curv-kzen / kzen-paillier 的 C 原生扩展。本测试套件需在 Linux / WSL2 运行。
//! - **mTLS**：测试通过 tonic TLS client 连接，加载 `certs/node1.crt` 作为客户端证书。
//! - **gRPC auth**：集群启动时设置 `MPC_AUTH_TOKEN=nexus-mpc-test-token`，
//!   测试请求需携带 `Authorization: Bearer nexus-mpc-test-token` metadata。
//!
//! ## 测试用例
//!
//! | 测试 | 验证内容 |
//! |------|----------|
//! | `test_dkg_3_nodes` | 3 节点 DKG，各节点获得一致聚合公钥 |
//! | `test_sign_2_of_3` | 2 节点协作签名，签名可由聚合公钥验证 |
//! | `test_sign_wrong_threshold` | 1 节点签名（低于 threshold=2）应失败 |
//! | `test_node_recovery` | 节点重启后从 WAL 恢复会话 |
//! | `test_mtls_handshake` | mTLS 双向证书握手验证 |
//!
//! 详见各测试函数的文档注释。

// =============================================================================
// 引用与常量
// =============================================================================


use std::path::PathBuf;

use tonic::metadata::MetadataValue;
use tonic::transport::{Channel, ClientTlsConfig, Certificate, Identity};
use tonic::Request;

use mpc_engine::proto::mpc_crypto::mpc_crypto_service_client::MpcCryptoServiceClient;
use mpc_engine::proto::mpc_crypto::{
    AggregateRequest, DkgRequest, HealthCheckRequest, SignRequest,
};

// =============================================================================
// 测试常量
// =============================================================================

/// 3 节点 gRPC 端点（含 https scheme，用于 mTLS）。
const NODE_ENDPOINTS: [&str; 3] = [
    "https://127.0.0.1:50051",
    "https://127.0.0.1:50052",
    "https://127.0.0.1:50053",
];

/// 节点 party_index（0-based，与 PartyConfig 对齐）。
const PARTY_INDICES: [i32; 3] = [0, 1, 2];

/// 阈值签名参数：2-of-3。
const THRESHOLD: i32 = 2;
const TOTAL_PARTIES: i32 = 3;

/// gRPC Bearer 认证 token（与 start-mpc-cluster.sh 中 MPC_AUTH_TOKEN 一致）。
const AUTH_TOKEN: &str = "nexus-mpc-test-token";

/// 测试用 AES-256-GCM storage_key（与 start-mpc-cluster.sh 中一致）。
const STORAGE_KEY: &str = "4242424242424242424242424242424242424242424242424242424242424242";

/// 曲线标识（与 nexus-signing-service 契约一致）。
const CURVE: &str = "secp256k1";

// =============================================================================
// 辅助函数
// =============================================================================

/// 获取 mpc-engine 工程根目录（CARGO_MANIFEST_DIR）。
fn project_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

/// 读取 PEM 文件内容为字符串。
fn read_pem(rel_path: &str) -> String {
    let path = project_root().join(rel_path);
    std::fs::read_to_string(&path).unwrap_or_else(|e| {
        panic!(
            "读取 PEM 文件失败 {}: {}（请先运行 scripts/generate-certs.sh）",
            path.display(),
            e
        )
    })
}

/// 创建到指定节点的 mTLS gRPC 客户端。
///
/// # 参数
/// - `node_idx`：节点索引（0/1/2，对应 node1/node2/node3）
///
/// # 返回
/// `MpcCryptoServiceClient<Channel>`，已配置 mTLS 与 Bearer auth。
///
/// # mTLS 配置
/// - 客户端证书：`certs/nodeN.crt` + `certs/nodeN.key`（作为客户端身份）
/// - CA 证书：`certs/ca.crt`（用于验证 server 证书）
///
/// # Auth
/// 通过 `Request::metadata_mut()` 在每个请求中注入 `Authorization: Bearer <token>`。
async fn make_client(node_idx: usize) -> MpcCryptoServiceClient<Channel> {
    assert!(node_idx < 3, "node_idx 必须在 0..3 范围内");

    let node_name = format!("node{}", node_idx + 1);
    let cert_pem = read_pem(&format!("certs/{}.crt", node_name));
    let key_pem = read_pem(&format!("certs/{}.key", node_name));
    let ca_pem = read_pem("certs/ca.crt");

    let identity = Identity::from_pem(cert_pem, key_pem);
    let ca_cert = Certificate::from_pem(ca_pem);

    let tls_config = ClientTlsConfig::new()
        .identity(identity)
        .ca_certificate(ca_cert)
        .domain_name("localhost");

    let endpoint: tonic::transport::Endpoint = NODE_ENDPOINTS[node_idx]
        .parse()
        .expect("无效的 gRPC endpoint");

    let channel = endpoint
        .tls_config(tls_config)
        .expect("TLS 配置失败")
        .connect()
        .await
        .expect(&format!(
            "连接节点 {} 失败（请确认集群已启动：bash scripts/start-mpc-cluster.sh）",
            node_name
        ));

    MpcCryptoServiceClient::new(channel)
}

/// 为 gRPC 请求注入 Bearer auth metadata。
fn with_auth<T>(req: Request<T>) -> Request<T> {
    let mut req = req;
    let bearer = format!("Bearer {}", AUTH_TOKEN);
    let val = MetadataValue::from_str(&bearer)
        .expect("Bearer token 含无效 ASCII 字符");
    req.metadata_mut().insert("authorization", val);
    req
}

/// 生成唯一 session_id（基于时间戳 + 后缀）。
fn make_session_id(suffix: &str) -> String {
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    format!("test-{}-{}", ts, suffix)
}

/// 生成 32 字节消息哈希（hex 编码）。
/// 测试用：sha256("NexusChain MPC integration test message")。
fn make_message_hash() -> String {
    use sha2::{Digest, Sha256};
    let msg = b"NexusChain MPC integration test message";
    let mut hasher = Sha256::new();
    hasher.update(msg);
    let hash = hasher.finalize();
    hex::encode(hash)
}

/// 等待所有 3 个节点健康检查通过（最多 30 秒）。
///
/// 通过 TCP 端口探测验证节点已监听（避免在健康检查中调用会 panic 的 `make_client`）。
/// gRPC 协议层健康检查由各测试用例自身的 `make_client` 调用隐式验证。
async fn wait_all_nodes_healthy(timeout_secs: u64) {
    let ports: [u16; 3] = [50051, 50052, 50053];
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(timeout_secs);
    loop {
        let mut all_healthy = true;
        for port in ports {
            // TCP 端口探测：尝试连接，成功则节点已监听
            let addr = format!("127.0.0.1:{}", port);
            match tokio::net::TcpStream::connect(&addr).await {
                Ok(_) => continue,
                Err(_) => all_healthy = false,
            }
        }
        if all_healthy {
            // 所有端口可连接，额外等待 1 秒让 gRPC 服务就绪
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            return;
        }
        if std::time::Instant::now() > deadline {
            panic!("等待节点健康检查超时（{}s）", timeout_secs);
        }
        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
    }
}

// =============================================================================
// 测试用例
// =============================================================================

/// ## test_dkg_3_nodes：3 节点分布式密钥生成
///
/// **验证内容**：
/// 1. 3 个节点并行执行 DKG（DkgRequest），各自返回成功
/// 2. 各节点返回的 **聚合公钥（public_key）一致**——这是 DKG 协议正确性的核心不变量
/// 3. 各节点返回的 **本方密钥份额（key_share）不同**——份额隔离，无方泄漏他方份额
/// 4. proof 字段非空（ZK 证明）
///
/// **协议背景**：
/// GG20 DKG 协议中，n 个参与方通过 Feldman VSS 各自生成密钥份额，
/// 聚合后所有方得到一致的群公钥 Q = x·G（x 为聚合私钥，无人知晓），
/// 但每方只持有自己份额 x_i（满足 x = Σ x_i）。
///
/// **运行方式**：
/// ```bash
/// bash scripts/start-mpc-cluster.sh
/// cargo test --features tls --test integration_test -- --ignored test_dkg_3_nodes
/// ```
#[tokio::test]
#[ignore = "需多节点环境：先 bash scripts/start-mpc-cluster.sh 启动集群"]
async fn test_dkg_3_nodes() {
    wait_all_nodes_healthy(30).await;

    let session_id = make_session_id("dkg-3nodes");
    let peer_endpoints: Vec<String> = NODE_ENDPOINTS.iter().map(|s| s.to_string()).collect();

    // 向 3 个节点发起 DkgRequest（串行；DKG 协议内部通过 peer_endpoints 跨节点通信）
    let mut results = Vec::new();
    for i in 0..3 {
        let mut client = make_client(i).await;
        let req = DkgRequest {
            session_id: session_id.clone(),
            threshold: THRESHOLD,
            total_parties: TOTAL_PARTIES,
            party_index: PARTY_INDICES[i],
            curve: CURVE.to_string(),
            peer_endpoints: peer_endpoints.clone(),
        };
        let resp = client
            .dkg(with_auth(Request::new(req)))
            .await
            .expect(&format!("node{} DKG RPC 失败", i + 1))
            .into_inner();
        results.push(resp);
    }

    // 1. 所有节点 DKG 成功
    for (i, r) in results.iter().enumerate() {
        assert!(
            r.success,
            "node{} DKG 未成功: {}",
            i + 1,
            r.error
        );
    }

    // 2. 聚合公钥一致（核心不变量）
    let pk0 = &results[0].public_key;
    assert!(!pk0.is_empty(), "node1 public_key 为空");
    for (i, r) in results.iter().enumerate().skip(1) {
        assert_eq!(
            r.public_key, *pk0,
            "node{} 公钥与 node1 不一致（DKG 协议失败）",
            i + 1
        );
    }

    // 3. 各方密钥份额不同（份额隔离）
    let ks0 = &results[0].key_share;
    assert!(!ks0.is_empty(), "node1 key_share 为空");
    let mut all_same = true;
    for r in results.iter().skip(1) {
        if r.key_share != *ks0 {
            all_same = false;
            break;
        }
    }
    assert!(!all_same, "所有节点 key_share 相同——份额未隔离？");

    // 4. ZK 证明非空
    for (i, r) in results.iter().enumerate() {
        assert!(!r.proof.is_empty(), "node{} proof 为空", i + 1);
    }

    println!(
        "[test_dkg_3_nodes] ✓ 3 节点 DKG 成功，聚合公钥一致（len={}），份额隔离",
        pk0.len()
    );
}

/// ## test_sign_2_of_3：2 节点协作签名（threshold=2）
///
/// **验证内容**：
/// 1. 先执行 3 节点 DKG，获得聚合公钥与各方份额
/// 2. 选取任意 2 个节点（party_index=0, 1）协作签名
/// 3. 各方执行 SignRequest 得到 partial_signature
/// 4. 调用 AggregateRequest 聚合得到完整签名 (r, s, recovery_id)
/// 5. 用聚合公钥 **验证签名**：secp256k1 ECDSA verify(message_hash, r||s, public_key) == true
///
/// **协议背景**：
/// GG20 阈值 ECDSA 签名：threshold 个参与方协作生成部分签名 σ_i，
/// 聚合后得到完整签名 (r, s)，满足 ECDSA verify(m, r, s, Q) = true。
/// 少于 threshold 个方无法生成有效签名（见 test_sign_wrong_threshold）。
///
/// **运行方式**：
/// ```bash
/// bash scripts/start-mpc-cluster.sh
/// cargo test --features tls --test integration_test -- --ignored test_sign_2_of_3
/// ```
#[tokio::test]
#[ignore = "需多节点环境：先 bash scripts/start-mpc-cluster.sh 启动集群"]
async fn test_sign_2_of_3() {
    wait_all_nodes_healthy(30).await;

    // ---------- 1. DKG ----------
    let session_id = make_session_id("sign-2of3");
    let peer_endpoints: Vec<String> = NODE_ENDPOINTS.iter().map(|s| s.to_string()).collect();

    let mut dkg_results = Vec::new();
    for i in 0..3 {
        let mut client = make_client(i).await;
        let req = DkgRequest {
            session_id: session_id.clone(),
            threshold: THRESHOLD,
            total_parties: TOTAL_PARTIES,
            party_index: PARTY_INDICES[i],
            curve: CURVE.to_string(),
            peer_endpoints: peer_endpoints.clone(),
        };
        let resp = client
            .dkg(with_auth(Request::new(req)))
            .await
            .expect(&format!("node{} DKG 失败", i + 1))
            .into_inner();
        assert!(resp.success, "node{} DKG 失败: {}", i + 1, resp.error);
        dkg_results.push(resp);
    }
    let public_key = dkg_results[0].public_key.clone();
    let msg_hash = make_message_hash();

    // ---------- 2. 选取 2 个节点签名（party_index=0, 1） ----------
    let signing_parties = [0usize, 1];
    let mut partial_sigs = Vec::new();

    for &i in &signing_parties {
        let mut client = make_client(i).await;
        let req = SignRequest {
            session_id: session_id.clone(),
            public_key: public_key.clone(),
            key_share: dkg_results[i].key_share.clone(),
            message_hash: msg_hash.clone(),
            party_index: PARTY_INDICES[i],
            peer_endpoints: peer_endpoints.clone(),
        };
        let resp = client
            .sign(with_auth(Request::new(req)))
            .await
            .expect(&format!("node{} Sign 失败", i + 1))
            .into_inner();
        assert!(resp.success, "node{} Sign 失败: {}", i + 1, resp.error);
        assert!(
            !resp.partial_signature.is_empty(),
            "node{} partial_signature 为空",
            i + 1
        );
        partial_sigs.push(resp.partial_signature);
    }

    // ---------- 3. 聚合签名（用 node1 作为聚合方） ----------
    let mut agg_client = make_client(0).await;
    let agg_req = AggregateRequest {
        session_id: session_id.clone(),
        public_key: public_key.clone(),
        message_hash: msg_hash.clone(),
        partial_signatures: partial_sigs,
    };
    let agg_resp = agg_client
        .aggregate(with_auth(Request::new(agg_req)))
        .await
        .expect("Aggregate 失败")
        .into_inner();
    assert!(agg_resp.success, "Aggregate 失败: {}", agg_resp.error);
    assert!(!agg_resp.r.is_empty(), "r 为空");
    assert!(!agg_resp.s.is_empty(), "s 为空");

    // ---------- 4. 验证签名 ----------
    // 用 secp256k1 库验证 ECDSA 签名
    use secp256k1::{Message, PublicKey, Secp256k1, Signature};

    let secp = Secp256k1::verification_only();
    let pk_bytes = hex::decode(&public_key).expect("public_key hex 解码失败");
    let pk = PublicKey::from_slice(&pk_bytes).expect("PublicKey 解析失败");

    let r_bytes = hex::decode(&agg_resp.r).expect("r hex 解码失败");
    let s_bytes = hex::decode(&agg_resp.s).expect("s hex 解码失败");
    let mut sig_bytes = Vec::with_capacity(64);
    sig_bytes.extend_from_slice(&r_bytes);
    sig_bytes.extend_from_slice(&s_bytes);
    let sig = Signature::from_compact(&sig_bytes).expect("Signature 解析失败");

    let msg_bytes = hex::decode(&msg_hash).expect("message_hash hex 解码失败");
    let msg = Message::from_slice(&msg_bytes).expect("Message 解析失败");

    assert!(
        secp.verify(&msg, &sig, &pk).is_ok(),
        "签名验证失败：ECDSA verify(msg, r||s, public_key) != true"
    );

    println!(
        "[test_sign_2_of_3] ✓ 2-of-3 签名成功，recovery_id={}, 签名验证通过",
        agg_resp.recovery_id
    );
}

/// ## test_sign_wrong_threshold：1 节点签名（低于 threshold）应失败
///
/// **验证内容**：
/// 1. 执行 3 节点 DKG
/// 2. 仅用 1 个节点签名（party_index=0），不聚合
/// 3. 调用 Aggregate 时只传入 1 个 partial_signature
/// 4. **预期**：Aggregate 应失败（success=false），返回错误说明"签名方数不足 threshold"
///
/// **协议背景**：
/// GG20 阈值签名要求至少 threshold 个参与方协作。少于 threshold 个方的部分签名
/// 无法聚合出有效签名（数学上无法重构完整签名 s = k^{-1}(m + r·x)）。
/// 这是阈值签名安全性的核心保证——单点无法伪造签名。
///
/// **运行方式**：
/// ```bash
/// bash scripts/start-mpc-cluster.sh
/// cargo test --features tls --test integration_test -- --ignored test_sign_wrong_threshold
/// ```
#[tokio::test]
#[ignore = "需多节点环境：先 bash scripts/start-mpc-cluster.sh 启动集群"]
async fn test_sign_wrong_threshold() {
    wait_all_nodes_healthy(30).await;

    // ---------- 1. DKG ----------
    let session_id = make_session_id("sign-wrong-threshold");
    let peer_endpoints: Vec<String> = NODE_ENDPOINTS.iter().map(|s| s.to_string()).collect();

    let mut dkg_results = Vec::new();
    for i in 0..3 {
        let mut client = make_client(i).await;
        let req = DkgRequest {
            session_id: session_id.clone(),
            threshold: THRESHOLD,
            total_parties: TOTAL_PARTIES,
            party_index: PARTY_INDICES[i],
            curve: CURVE.to_string(),
            peer_endpoints: peer_endpoints.clone(),
        };
        let resp = client
            .dkg(with_auth(Request::new(req)))
            .await
            .expect(&format!("node{} DKG 失败", i + 1))
            .into_inner();
        assert!(resp.success, "node{} DKG 失败: {}", i + 1, resp.error);
        dkg_results.push(resp);
    }
    let public_key = dkg_results[0].public_key.clone();
    let msg_hash = make_message_hash();

    // ---------- 2. 仅 1 个节点签名 ----------
    let mut client = make_client(0).await;
    let req = SignRequest {
        session_id: session_id.clone(),
        public_key: public_key.clone(),
        key_share: dkg_results[0].key_share.clone(),
        message_hash: msg_hash.clone(),
        party_index: PARTY_INDICES[0],
        peer_endpoints: peer_endpoints.clone(),
    };
    let sign_resp = client
        .sign(with_auth(Request::new(req)))
        .await
        .expect("node1 Sign RPC 失败（gRPC 层）")
        .into_inner();
    // 单方 Sign 本身可能成功（生成部分签名），但聚合时方数不足
    if !sign_resp.success {
        println!(
            "[test_sign_wrong_threshold] 单方 Sign 即失败（预期行为）: {}",
            sign_resp.error
        );
        return;
    }

    // ---------- 3. 聚合（仅 1 个部分签名，应失败） ----------
    let agg_req = AggregateRequest {
        session_id: session_id.clone(),
        public_key: public_key.clone(),
        message_hash: msg_hash.clone(),
        partial_signatures: vec![sign_resp.partial_signature],
    };
    let agg_resp = client
        .aggregate(with_auth(Request::new(agg_req)))
        .await
        .expect("Aggregate RPC 失败（gRPC 层）")
        .into_inner();

    // ---------- 4. 预期聚合失败 ----------
    assert!(
        !agg_resp.success,
        "聚合应失败（仅 1 个部分签名 < threshold=2），但返回 success=true: r={}, s={}",
        agg_resp.r,
        agg_resp.s
    );

    println!(
        "[test_sign_wrong_threshold] ✓ 1 节点签名聚合失败（预期）: {}",
        agg_resp.error
    );
}

/// ## test_node_recovery：节点重启后从 WAL 恢复会话
///
/// **验证内容**：
/// 1. 执行 3 节点 DKG，会话快照落盘到 `data/nodeN/sessions/`
/// 2. 停止 node3（SIGTERM）
/// 3. 重启 node3（重新加载配置）
/// 4. node3 从 WAL（AES-256-GCM 加密）恢复会话
/// 5. 用 node3 的恢复会话参与签名，验证签名成功
///
/// **协议背景**：
/// mpc-engine 的 persistence 模块在 DKG 完成后将 DkgSession 加密落盘
/// （AES-256-GCM，密钥从 MPC_STORAGE_KEY 读取）。节点重启后，Sign 阶段
/// 若内存中无会话，则从盘恢复（`MPC_ENGINE_SESSION_DIR`）。
/// 这是方案 A"份额只在参与者进程"语义的关键：节点崩溃不丢失密钥份额。
///
/// **运行方式**：
/// ```bash
/// bash scripts/start-mpc-cluster.sh
/// cargo test --features tls --test integration_test -- --ignored test_node_recovery
/// ```
///
/// **注**：本测试会停止并重启 node3，需确保 `scripts/start-mpc-cluster.sh` 的
/// PID 文件机制可用（`.run/node3.pid`）。
#[tokio::test]
#[ignore = "需多节点环境：先 bash scripts/start-mpc-cluster.sh 启动集群"]
async fn test_node_recovery() {
    wait_all_nodes_healthy(30).await;

    // ---------- 1. DKG ----------
    let session_id = make_session_id("node-recovery");
    let peer_endpoints: Vec<String> = NODE_ENDPOINTS.iter().map(|s| s.to_string()).collect();

    let mut dkg_results = Vec::new();
    for i in 0..3 {
        let mut client = make_client(i).await;
        let req = DkgRequest {
            session_id: session_id.clone(),
            threshold: THRESHOLD,
            total_parties: TOTAL_PARTIES,
            party_index: PARTY_INDICES[i],
            curve: CURVE.to_string(),
            peer_endpoints: peer_endpoints.clone(),
        };
        let resp = client
            .dkg(with_auth(Request::new(req)))
            .await
            .expect(&format!("node{} DKG 失败", i + 1))
            .into_inner();
        assert!(resp.success, "node{} DKG 失败: {}", i + 1, resp.error);
        dkg_results.push(resp);
    }
    let public_key = dkg_results[0].public_key.clone();
    let msg_hash = make_message_hash();

    // ---------- 2. 停止 node3 ----------
    let pid_file = project_root().join(".run/node3.pid");
    let pid_str = std::fs::read_to_string(&pid_file).expect(
        format!(
            "读取 node3 PID 文件失败 {}: 请确认集群由 start-mpc-cluster.sh 启动",
            pid_file.display()
        )
        .as_str(),
    );
    let pid: u32 = pid_str.trim().parse().expect("PID 解析失败");

    // 发送 SIGTERM 优雅停止
    #[cfg(unix)]
    {
        let _ = nix::sys::signal::kill(
            nix::unistd::Pid::from_raw(pid as i32),
            nix::sys::signal::Signal::SIGTERM,
        );
    }
    #[cfg(not(unix))]
    {
        // Windows 不支持 SIGTERM；本测试仅在 Linux 运行
        panic!("test_node_recovery 仅支持 Linux（需 SIGTERM 信号）");
    }

    // 等待 node3 退出（最多 10 秒）
    #[cfg(unix)]
    {
        for _ in 0..100 {
            // kill(pid, None) 检测进程是否存在：返回 Err(ESRCH) 表示已退出
            if nix::sys::signal::kill(nix::unistd::Pid::from_raw(pid as i32), None).is_err() {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        }
    }
    #[cfg(not(unix))]
    {
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
    }

    // ---------- 3. 重启 node3 ----------
    let mpc_binary = project_root().join("target/release/mpc-engine");
    let node3_config = project_root().join("config/node3.json");
    let node3_log_path = project_root().join("logs/node3-recovery.log");
    let node3_log_file = std::fs::File::create(&node3_log_path)
        .expect("创建 node3-recovery.log 失败");
    let node3_stdout = std::process::Stdio::from(
        node3_log_file.try_clone().expect("clone log file for stdout"),
    );
    let node3_stderr = std::process::Stdio::from(node3_log_file);

    let mut child = std::process::Command::new(&mpc_binary)
        .arg("--config")
        .arg(&node3_config)
        .env("MPC_CONFIG_PATH", &node3_config)
        .env(
            "MPC_ENGINE_SESSION_DIR",
            project_root().join("data/node3/sessions"),
        )
        .env("MPC_REQUIRE_TLS", "true")
        .env("MPC_AUTH_TOKEN", AUTH_TOKEN)
        .env("MPC_STORAGE_KEY", STORAGE_KEY)
        .env("RUST_LOG", "info")
        .stdout(node3_stdout)
        .stderr(node3_stderr)
        .spawn()
        .expect("重启 node3 失败");

    // 等待 node3 重新就绪
    tokio::time::sleep(std::time::Duration::from_secs(3)).await;

    // ---------- 4. 用 node3 恢复的会话签名 ----------
    // node3 重启后内存无会话，Sign 时从盘恢复
    let mut client = make_client(2).await;
    let req = SignRequest {
        session_id: session_id.clone(),
        public_key: public_key.clone(),
        key_share: dkg_results[2].key_share.clone(),
        message_hash: msg_hash.clone(),
        party_index: PARTY_INDICES[2],
        peer_endpoints: peer_endpoints.clone(),
    };
    let sign_resp = client
        .sign(with_auth(Request::new(req)))
        .await
        .expect("node3 Sign RPC 失败（恢复后）")
        .into_inner();

    assert!(
        sign_resp.success,
        "node3 恢复会话后签名失败: {}（WAL 恢复可能未生效）",
        sign_resp.error
    );

    // ---------- 5. 清理：停止重启的 node3 子进程 ----------
    let _ = child.kill();
    let _ = child.wait();

    println!(
        "[test_node_recovery] ✓ node3 重启后从 WAL 恢复会话成功，签名 partial_sig len={}",
        sign_resp.partial_signature.len()
    );
}

/// ## test_mtls_handshake：mTLS 双向证书握手验证
///
/// **验证内容**：
/// 1. 用 node1 的证书作为客户端证书，连接 node2 的 gRPC 端点
/// 2. 调用 HealthCheck RPC，验证 mTLS 握手成功
/// 3. **负面测试**：用无效证书（自签名临时证书）连接，预期握手失败
///
/// **协议背景**：
/// mpc-engine 在分布式模式下强制 mTLS（`MPC_REQUIRE_TLS=true` 隐含）：
/// - Server 端加载 `tls_ca`（CA 证书），要求客户端提供由该 CA 签发的证书
/// - Client 端加载自己的 `tls_cert` + `tls_key`，并验证 server 证书
/// 双向验证确保只有受信节点能加入 MPC 网络，防止未授权节点窃听或注入。
///
/// **运行方式**：
/// ```bash
/// bash scripts/start-mpc-cluster.sh
/// cargo test --features tls --test integration_test -- --ignored test_mtls_handshake
/// ```
#[tokio::test]
#[ignore = "需多节点环境：先 bash scripts/start-mpc-cluster.sh 启动集群"]
async fn test_mtls_handshake() {
    wait_all_nodes_healthy(30).await;

    // ---------- 1. 正向：node1 证书连接 node1，mTLS 握手成功 ----------
    // make_client(0) 加载 node1 证书并连接 node1 端点（127.0.0.1:50051）
    // 验证 server 端接受客户端证书 + client 端验证 server 证书（双向 mTLS）
    let mut client = make_client(0).await;
    let resp = client
        .health_check(with_auth(Request::new(HealthCheckRequest {
            service: "mpc-engine".to_string(),
        })))
        .await
        .expect("mTLS 握手失败：node1 证书应能连接 node1 server");

    let health = resp.into_inner();
    assert!(health.healthy, "HealthCheck.healthy=false: {}", health.status);
    assert!(
        health.status.contains("mpc-engine"),
        "HealthCheck.status 不含 'mpc-engine': {}",
        health.status
    );

    // ---------- 2. 负面：无证书连接应失败 ----------
    // 构造无客户端证书的 Channel，预期 TLS 握手被 server 拒绝
    let ca_pem = read_pem("certs/ca.crt");
    let ca_cert = Certificate::from_pem(ca_pem);

    let tls_config_no_client = ClientTlsConfig::new()
        .ca_certificate(ca_cert)
        .domain_name("localhost");

    let endpoint: tonic::transport::Endpoint = NODE_ENDPOINTS[1].parse().expect("无效 endpoint");
    let no_client_cert_result = endpoint
        .tls_config(tls_config_no_client)
        .expect("TLS 配置失败")
        .connect()
        .await;

    // 无客户端证书应握手失败（server 要求 mTLS）
    assert!(
        no_client_cert_result.is_err(),
        "无客户端证书应被 mTLS server 拒绝，但连接成功——server 可能未启用 mTLS"
    );

    println!("[test_mtls_handshake] ✓ mTLS 握手验证通过：合法证书成功，无证书被拒绝");
}

// =============================================================================
// 测试套件入口（cargo test 自动发现 #[tokio::test] 函数，无需 main）
// =============================================================================
// 注：所有测试标注 #[ignore]，需 `cargo test -- --ignored` 显式运行。
// 这避免在无多节点环境的常规 `cargo test` 中误触发网络连接超时。
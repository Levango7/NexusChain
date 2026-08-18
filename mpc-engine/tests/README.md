# MPC 多节点集成测试

本目录包含 NexusChain mpc-engine 的多节点端到端集成测试，验证 GG20 DKG、阈值签名、节点恢复与 mTLS 握手在 3 节点分布式部署下的正确性。

## 目录结构

```
mpc-engine/
├── scripts/
│   ├── generate-certs.sh       # mTLS 证书生成脚本（CA + 3 节点证书）
│   └── start-mpc-cluster.sh    # 3 节点集群启动/停止脚本
├── config/
│   ├── node1.toml              # 节点 1 配置模板（人类可读）
│   ├── node2.toml
│   ├── node3.toml
│   └── nodeN.json              # 运行时实际加载的配置（由脚本自动生成）
├── certs/                      # mTLS 证书输出目录
│   ├── ca.crt / ca.key
│   ├── node1.crt / node1.key
│   ├── node2.crt / node2.key
│   └── node3.crt / node3.key
├── data/
│   └── nodeN/sessions/         # 各节点会话快照（AES-256-GCM 加密）
├── logs/
│   └── nodeN.log               # 各节点运行日志
└── tests/
    ├── integration_test.rs     # 集成测试（5 个用例，均 #[ignore]）
    └── README.md               # 本文件
```

## 环境要求

### 必需

| 依赖 | 版本 | 用途 |
|------|------|------|
| Linux / WSL2 | — | secp256k1 / kzen-paillier 原生库编译需要 gcc + dlltool |
| rustc + cargo | 1.70+ | Rust 工具链 |
| gcc | 4.8+ | C 编译器（链接原生库） |
| openssl | 1.1.1+ | 证书生成 |
| protoc | 3.0+ | gRPC stub 生成（build.rs） |

### 可选

| 依赖 | 用途 |
|------|------|
| grpcurl | 健康检查（gRPC 协议层）；缺失时退化为 TCP 端口探测 |
| nc (netcat) | TCP 端口探测回退方案 |

### Windows 限制

**Windows 环境无法运行本测试套件**：

- `multi-party-ecdsa` / `curv-kzen` / `kzen-paillier` 依赖 C 原生扩展，编译需要 `gcc.exe` + `dlltool.exe`
- Windows MSVC 工具链不提供 `dlltool`，GNU 工具链需手动安装 MinGW + binutils
- `test_node_recovery` 使用 `nix` crate 发送 SIGTERM 信号，仅 Unix 可用

**解决方案**：在 WSL2 / Docker Linux 容器中运行。

## 运行步骤

### 1. 启动 MPC 集群

```bash
cd mpc-engine

# 前台启动（Ctrl+C 停止）
bash scripts/start-mpc-cluster.sh

# 或后台启动（daemon 模式）
bash scripts/start-mpc-cluster.sh -d
```

脚本执行流程：

1. 检查 `certs/` 下 mTLS 证书，缺失则调用 `generate-certs.sh` 生成
2. 编译 mpc-engine（`cargo build --features tls --release`）
3. 生成节点 JSON 配置（`config/nodeN.json`，从 TOML 模板等价转换）
4. 启动 3 个节点子进程，分别监听 50051/50052/50053
5. 健康检查等待所有节点就绪（最多 30 秒）
6. 输出节点 PID 与日志路径

### 2. 运行集成测试

```bash
# 运行所有集成测试
cargo test --features tls --test integration_test -- --ignored

# 运行单个测试
cargo test --features tls --test integration_test -- --ignored test_dkg_3_nodes
cargo test --features tls --test integration_test -- --ignored test_sign_2_of_3
cargo test --features tls --test integration_test -- --ignored test_sign_wrong_threshold
cargo test --features tls --test integration_test -- --ignored test_node_recovery
cargo test --features tls --test integration_test -- --ignored test_mtls_handshake

# 显示测试输出（println!）
cargo test --features tls --test integration_test -- --ignored --nocapture
```

**`--ignored` 是必须的**：所有测试标注了 `#[ignore]`，因它们需要多节点环境，不应在常规 `cargo test` 中触发。

### 3. 停止集群

```bash
bash scripts/start-mpc-cluster.sh -k
```

或前台模式下按 `Ctrl+C`。

## 测试用例

| 测试 | 验证内容 | 预期结果 |
|------|----------|----------|
| `test_dkg_3_nodes` | 3 节点 DKG，各节点获得一致聚合公钥 | 公钥一致，份额隔离，proof 非空 |
| `test_sign_2_of_3` | 2 节点协作签名，签名可由公钥验证 | ECDSA verify(msg, r‖s, Q) = true |
| `test_sign_wrong_threshold` | 1 节点签名（低于 threshold=2）应失败 | Aggregate 返回 success=false |
| `test_node_recovery` | 节点重启后从 WAL 恢复会话 | 重启后签名成功 |
| `test_mtls_handshake` | mTLS 双向证书握手验证 | 合法证书成功，无证书被拒绝 |

## 配置文件说明

### TOML 模板（`config/nodeN.toml`）

人类可读的配置模板，包含完整字段注释。**mpc-engine 当前实现使用 JSON 格式**（`PartyConfig` + `serde_json`），TOML 仅作为模板参考。

### JSON 配置（`config/nodeN.json`）

运行时实际加载的配置，由 `start-mpc-cluster.sh` 自动生成（若不存在）。字段与 `PartyConfig` 结构对齐：

```json
{
  "party_index": 0,
  "party_id": "party-0",
  "listen_addr": "127.0.0.1:50051",
  "peers": [
    {"party_index": 1, "party_id": "party-1", "endpoint": "https://127.0.0.1:50052"},
    {"party_index": 2, "party_id": "party-2", "endpoint": "https://127.0.0.1:50053"}
  ],
  "storage_key": "4242...4242",
  "storage_key_version": 1,
  "storage_key_source": "plain",
  "tls_cert": "certs/node1.crt",
  "tls_key": "certs/node1.key",
  "tls_ca": "certs/ca.crt"
}
```

## 已知限制

1. **Windows 不支持**：缺 gcc/dlltool，无法编译原生库；`test_node_recovery` 需 Unix 信号
2. **mTLS 域名**：证书 SAN 包含 `localhost` + `127.0.0.1`，仅适用于本地验证；生产环境需包含实际域名
3. **storage_key**：测试用固定密钥 `4242...4242`，生产环境必须从 KMS / 环境变量读取
4. **gRPC auth**：测试用固定 token `nexus-mpc-test-token`，生产环境需强随机 token
5. **节点恢复测试**：`test_node_recovery` 会停止并重启 node3，需确保集群由 `start-mpc-cluster.sh` 启动（依赖 PID 文件）
6. **健康检查回退**：无 grpcurl 时退化为 TCP 端口探测，仅验证端口监听不验证 gRPC 协议

## 故障排查

### 节点启动失败

```bash
# 查看节点日志
tail -100 logs/node1.log
tail -100 logs/node2.log
tail -100 logs/node3.log

# 查看编译日志
tail -100 logs/build.log
```

### 健康检查超时

```bash
# 检查端口监听
ss -tlnp | grep -E '5005[123]'

# 手动 gRPC 调用
grpcurl -plaintext 127.0.0.1:50051 nexus.mpc.MpcCryptoService/HealthCheck
```

### 证书问题

```bash
# 重新生成证书
bash scripts/generate-certs.sh -f

# 验证证书
openssl verify -CAfile certs/ca.crt certs/node1.crt
openssl x509 -in certs/node1.crt -text -noout | grep -A2 "Subject Alternative Name"
```

### 端口占用

```bash
# 查看占用进程
lsof -i :50051
# 修改 config/nodeN.toml 中的 listen_addr 与端口
```
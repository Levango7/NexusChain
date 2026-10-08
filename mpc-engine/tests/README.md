# mpc-engine 集成测试

本目录包含 CGGMP21 门限签名的端到端集成测试。

> **GG20 退役说明（2026-10-08，PLAN-001-R2）**：原 `integration_test.rs`
> （GG20 3 节点集群 DKG/Sign/恢复/mTLS 五用例）随 GG20 路径一并删除。
> 真实多进程集群 E2E 现由 Java 侧 `CggmpMpcE2EClusterTest` 承担（见下）。

## 测试套件（进程内，`cargo test`）

| 文件 | 验证内容 |
|------|----------|
| `cggmp_dkg_sim.rs` | 三方 keygen 仿真：批量 `IncompleteKeyShare` 产出与 `validate` 通过 |
| `cggmp_threshold_e2e.rs` | 三方 keygen → aux → assembleShare → **2-of-3 sign** → 验签里程碑 |
| `cggmp_rpc_e2e.rs` | 进程内起 3 个 tonic server，经 `MpcCryptoService` RPC 面（`Cg*` + relay）跑全链路 |
| `cggmp_persistence_recovery.rs` | 份额落盘（NXC1 信封）→ "重启"（同 StorageCtx 新实例）→ 恢复 → 签名 |

```bash
cd mpc-engine
cargo test --features tls --test cggmp_dkg_sim --test cggmp_threshold_e2e \
  --test cggmp_rpc_e2e --test cggmp_persistence_recovery
```

CI：`build-and-test` job 的「MPC CGGMP21 E2E (in-process protocol tests)」步骤
（`.github/workflows/ci.yml`）；lib 单测见「Rust tests (mpc-engine, lib)」步骤。

**运行时长提示**：aux_info 的 Paillier 安全素数生成是重活——三个协议 E2E
各约 1-2 分钟（debug 构建本机实测 ~100s/套件），CI 会随缓存波动。

## 真实多进程集群 E2E（Java 侧，CI 独立 job）

`nexus-signing-service` 的 `CggmpMpcE2EClusterTest` 在 JVM 内拉起 3 个
mpc-engine 子进程（经 `MPC_ENGINE_BIN` 指定二进制），走真实 gRPC + mTLS
完成生产路径 2-of-3 签名（含份额隔离断言）。

- CI job：`mpc-java-cluster-e2e`（`.github/workflows/ci.yml`）
- 该 job 流程：build mpc-engine（release + tls）→ `start-mpc-cluster.sh --setup-only`
  生成证书与节点配置 → `./gradlew :nexus-signing-service:test -PincludeClusterE2E
  --tests "....CggmpMpcE2EClusterTest"`

## 集群脚本（手动验证用）

```bash
cd mpc-engine
bash scripts/start-mpc-cluster.sh --setup-only  # 只生成 certs/ 与 config/nodeN.json
bash scripts/start-mpc-cluster.sh -d            # 起 3 节点（50051-50053）
bash scripts/start-mpc-cluster.sh -k            # 停止
```

配置文件：`config/nodeN.toml` 为人类可读模板；`config/nodeN.json` 是运行时实际
加载的 `PartyConfig`（由脚本生成）。存储密钥经 `MPC_STORAGE_KEY` 注入，证书由
`scripts/generate-certs.sh` 生成（CA + 3 节点，SAN=localhost/127.0.0.1）。

## 已知限制

1. **TLS SAN**：测试证书仅含 `localhost` + `127.0.0.1`，K8s 内连接引擎 Pod DNS 时
   客户端需 `NEX_MPC_ENGINE_TLS_OVERRIDE_AUTHORITY=localhost`（与生产 values 一致）。
2. **storage_key**：集群脚本生成一次性随机密钥（`MPC_STORAGE_KEY` 环境变量）；
   生产必须接 KMS/Secret，测试占位值不得复用。
3. **gRPC auth**：集群脚本 token 为测试值；生产需强随机 `MPC_AUTH_TOKEN`（两端一致）。
4. **Windows 本地构建**：cggmp21 链路依赖 rug/GMP，需 MSYS2（`m4`+`make`+`gcc`）；
   Git Bash 下 MSVC linker 会被 `/usr/bin/link` 遮蔽，改用
   `cargo +stable-x86_64-pc-windows-gnu`（CI 在 Linux，无此问题）。
5. **健康检查**：`grpcurl -plaintext 127.0.0.1:50051 nexus.mpc.MpcCryptoService/HealthCheck`
   可手工探活；无 grpcurl 时可用 `ss -tlnp | grep 50051` 粗查端口。

## 故障排查

```bash
# 节点日志 / 编译日志
tail -100 logs/node1.log
tail -100 logs/build.log

# 证书问题
bash scripts/generate-certs.sh -f
openssl verify -CAfile certs/ca.crt certs/node1.crt
openssl x509 -in certs/node1.crt -text -noout | grep -A2 "Subject Alternative Name"

# 端口占用
lsof -i :50051
```

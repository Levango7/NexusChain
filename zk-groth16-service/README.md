# zk-groth16-service 部署

真实 BN254 Groth16 验证服务（Rust arkworks + tonic gRPC + axum HTTP）。

## 接口

| 端点 | 用途 |
|---|---|
| `GET /health` | 健康检查（status/curve/engine） |
| `GET /v1/bench` | 性能基准（prove/verify 平均耗时） |
| `POST /v1/verify` | 演示电路验证 / 电路桥接验证（`circuit_json` 载荷，Java R1csToJsonBridge 对接） |
| `POST /v1/setup` | 按电路指纹幂等 setup（返回 vk_hex） |
| `POST /v1/prove` | 用持久化 pk 生成证明（返回 proof_hex） |
| `POST /v1/verify-sep` | 分离验证（外部证明 + 持久化 vk） |
| `POST /v1/setup-external` | 可信设置仪式产出导入（pk/vk hex） |

## Docker 部署（docker-compose）

```bash
docker compose up -d zk-groth16-service
# gRPC: 127.0.0.1:50061, HTTP: 127.0.0.1:50062
# setup 持久化: 命名卷 zk-groth16-setup（/setup）
```

## 可信设置仪式接入（生产）

```bash
# 方式 1：仪式产出经 API 导入
curl -X POST http://localhost:50062/v1/setup-external \
  -H "Content-Type: application/json" \
  -d '{"circuit_json":{...},"pk_hex":"...","vk_hex":"..."}'

# 方式 2：仪式产出目录挂载（容器内 GROTH16_EXTERNAL_SETUP_DIR=/external-setup）
#   <fingerprint>/pk.bin + vk.bin（二进制，ark-serialize）
```

## 安全

- pk 含 toxic waste（δ）：目录 0700、文件 0600；泄露可伪造证明——生产须隔离
- 默认确定性 seed（开发/演示）；生产走外部仪式（上述方式 1/2）

## 性能基线（演示电路，10 次平均）

- prove: ~30.5ms（prover 计算密集，Groth16 特性）
- verify: ~2.5ms（配对验证，适合链上/服务端验证）

# L2 ZK 可信设置仪式（Ceremony）——安全要求与操作指南

- 依据：`.codeartsdoer/specs/zk-groth16-realization/plan.md` ZK-A1-R4（G4：可信设置仪式未固化）
- 相关代码：`zk-groth16-service/src/setup_store.rs`（`import_external_setup`、`export_pk_hex`）、`zk-groth16-service/src/main.rs`（`/v1/setup`、`/v1/setup-external`、`/v1/verify-sep`）、`scripts/zk-setup-ceremony.sh`
- 目的：将"离线生成 Groth16 可信参数 → 导出 → 生产导入"流程固化为可重复执行、可审计、防篡改的标准仪式

## 1. 为什么需要仪式

Groth16 证明系统依赖一个 **可信设置（trusted setup）**：由随机秘密参数（toxic waste：α、β、γ、δ）生成 proving key（pk）与 verifying key（vk）。

- **若 toxic waste 泄露**：攻击者可伪造任意电路的证明，L2 状态根验证形同虚设。
- **若 vk 被篡改**：验证者使用错误 vk，恶意的错误证明可能被接受。
- **仪式目标**：在**离线/隔离**环境一次性生成参数，**立即销毁** toxic waste，只将 pk/vk 产物经**可信通道**传输至生产环境，并**验证指纹一致性**。

## 2. 仪式流程总览

```
┌─────────────────────── 离线/隔离环境（generate 阶段）───────────────────────┐
│  1. 启动 zk-groth16-service（无外网、无日志外发）                            │
│  2. scripts/zk-setup-ceremony.sh generate circuit.json bundle/              │
│     → 调 /v1/setup 生成真实 Groth16(BN254) 参数                              │
│     → 服务端立即销毁 toxic waste（不落盘、不导出，见 §3）                     │
│     → 导出 pk.hex / vk.hex / fingerprint / SHA256SUMS 至 bundle/（0600）     │
│  3. 离线介质（加密 U 盘等）传输 bundle 至生产环境                             │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   ▼
┌─────────────────────── 生产环境（import 阶段）───────────────────────────────┐
│  4. scripts/zk-setup-ceremony.sh import circuit.json bundle/ --verify        │
│     → SHA256SUMS 完整性校验（防篡改/损坏）                                    │
│     → 调 /v1/setup-external 导入 pk/vk（落盘 0600）                           │
│     → 校验 fingerprint 与仪式产物一致                                         │
│     → 冒烟验证（verify-sep 或 setup 幂等回归）                                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 3. 安全要求（强制）

| # | 要求 | 说明 |
| --- | --- | --- |
| S1 | **离线隔离执行 generate** | 生成环境不得连接生产网络/公网；建议物理隔离或至少网络隔离 + 日志外发关闭 |
| S2 | **toxic waste 立即销毁** | arkworks `Groth16::generate_random_parameters` 内部使用后即丢弃，不返回、不落盘；**严禁**修改代码导出 α/β/γ/δ |
| S3 | **产物最小化** | bundle 仅含 pk.hex/vk.hex/fingerprint/SHA256SUMS；**不导出**任何随机秘密 |
| S4 | **可信传输** | bundle 经加密通道（如 GPG 加密、加密移动介质）传输；传输后重新校验 SHA256SUMS |
| S5 | **权限收敛** | 脚本强制 bundle 目录 0700、产物文件 0600；服务端导入落盘 pk 0600 / vk 0644、目录 0700（`setup_store.rs` 已实现） |
| S6 | **导入后验证** | 必须执行 `--verify` 冒烟验证：证明-验证全链路或 setup 幂等指纹回归 |
| S7 | **审计留存** | 记录仪式时间、参与者、环境指纹、产物 SHA256；生产导入日志留档（服务端 `tracing` 已输出导入字节数） |

## 4. 命令示例

### 4.1 generate（离线环境）

```bash
# 离线机启动服务（setup 目录显式指定）
GROTH16_SETUP_DIR=/secure/groth16-setup ./zk-groth16-service &

# 执行仪式
SERVICE_URL=http://localhost:50062 \
  scripts/zk-setup-ceremony.sh generate \
  /secure/circuit.json /secure/ceremony-bundle-20260827/
# 产物: /secure/ceremony-bundle-20260827/{pk.hex,vk.hex,fingerprint,SHA256SUMS}
```

### 4.2 传输与校验

```bash
gpg -c /secure/ceremony-bundle-20260827.tar.gz   # 加密后走离线介质
# 生产侧解密后：
cd /secure/ceremony-bundle-20260827 && sha256sum -c SHA256SUMS
```

### 4.3 import（生产环境）

```bash
SERVICE_URL=http://localhost:50062 \
  scripts/zk-setup-ceremony.sh import \
  /etc/nexus/circuit.json /secure/ceremony-bundle-20260827/ --verify
# 预期输出: 导入成功 fingerprint=xxxx（与仪式产物一致）→ 冒烟验证通过
```

## 5. 幂等性与可重复性

- `/v1/setup` 按电路指纹幂等：同电路 JSON 重复 setup 返回同一 fingerprint，磁盘已存在则直接加载（`setup_store.rs` `load_or_setup`）。
- `import_external_setup` 覆盖导入：同一指纹重复导入以最后一次为准（日志记录字节数，便于审计）。
- **指纹来源**：`circuit_fingerprint` = 电路约束 JSON 的稳定 hash（`setup_store.rs:29`）。**同一电路定义必须产生同一指纹**，导入前脚本会强制比对，防止"张冠李戴"。

## 6. 故障排查

| 症状 | 可能原因 | 处理 |
| --- | --- | --- |
| `pk 二进制未找到` | 服务 setup 目录与 `GROTH16_SETUP_DIR` 不一致 | 确认 generate 时设置的环境变量与脚本一致 |
| `SHA256 校验失败` | 传输损坏或篡改 | 回到源头重新导出，走加密通道 |
| `指纹不一致` | 导入的 pk/vk 与 circuit.json 不匹配 | 核对 bundle 来源与电路版本 |
| `verify-sep 不可用` | 尚无该指纹持久化 proof | 脚本自动降级为 setup 幂等回归校验 |

## 7. 现状与后续

- 当前服务已实现：真实配对验证（`main.rs:51`）、setup 幂等持久化（`setup_store.rs:46`）、外部仪式导入（`setup_store.rs:155`）、分离验证 `/v1/verify-sep`。
- 本仪式脚本（`scripts/zk-setup-ceremony.sh`）将其固化为可重复执行流程，验收：**仪式脚本可重复执行，产出导入后 setup 持久化生效（G4 关闭）**。
- 后续：CI 增补仪式脚本语法/冒烟回归（ZK-A1-R5），生产部署 SOP 引用本文档。

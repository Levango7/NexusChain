# 方案：ZK 真实 Groth16（研究层解冻）

- **状态**：Approved（2026-08-15 审核通过）
- **日期**：2026-08-15
- **前置**：现有 `l2/zk/groth16/` 包（Groth16Proof/ProvingKey/VerifyingKey/Setup 定义）+ `DefaultZkProofSystem`（MOCK 占位，诚实标记 `MOCK|` 前缀）
- **目标**：把研究层冻结的 MOCK 证明替换为**真实 Groth16**（BN254 曲线 + R1CS 约束系统）

---

## 一、现状事实（代码已确认）

```
DefaultZkProofSystem: 所有证明带 "MOCK|" 前缀（诚实标记，verify 识别后按 mock 通过）
Groth16ProofSystem: 接口/数据结构定义存在，但非真实证明生成
R1csConstraintSystem: 真实 R1CS 约束构建（l2/zk/r1cs/ 包，测试存在）
ZkCurveParams: BN254 曲线参数定义
```

**核心缺口**：R1CS 约束（已有）→ 证明生成（缺真实实现）。

## 二、方案设计

### 方案 A：Arkworks 式 Java 实现（推荐，纯 Java 无 native 依赖）

```
引入 arkworks-rs 风格的纯 Java 配对库或自实现：
  1. BN254 配对运算（G1/G2 双线性对）——核心难点
  2. Groth16 证明生成（基于 R1CS witness + proving key）
  3. 验证算法（配对乘积等式）

可选库：
  - MCL（C++，Java 绑定，需 native）
  - circom-compatible 方案（需 Rust/Node 侧生成）
  - 自实现 BN254 配对（纯 Java，工程量大）
```

### 方案 B：离线预处理 + 验证侧真实（推荐组合）

```
  1. 证明生成：保留 MOCK（研究层内部使用，明确标记）
  2. **验证侧真实化**：新增 Groth16Verifier 真实实现——
     验证侧必须拒绝非真实证明（fail-closed）
  3. 真实证明由外部（Rust mpc-engine 侧或签名服务）生成后接入

  价值：即使证明生成暂缓，验证侧真实化保证"非真实证明不通过"，
       消除 MOCK 验证的信任漏洞（当前 mock 证明 verify 直接通过！）
```

### 方案 C：完全真实 Groth16（全链路）

```
  引入成熟库（如 BouncyCastle 无 Groth16；需 arkworks JNI 或自实现配对）
  实现 setup → prove → verify 全链路 + toxic waste 销毁
  预估：配对运算纯 Java 实现约 2-4 周，风险高（数学正确性验证难）
```

**推荐**：B（验证侧真实化 + fail-closed，立即落地）+ A 作为后续（纯 Java 证明生成研究）。

## 三、核心改动点（方案 B）

| 文件 | 改动 |
|---|---|
| `ZkVerifier` | 新增 `Groth16Verifier`：BN254 配对验证实现（G1/G2 配对库） |
| `DefaultZkProofSystem.verify` | MOCK 证明 → **拒绝**（除非显式 `allow-mock-verify` 配置，开发模式） |
| `ZkProofSystem` | 增加 proof type 区分（REAL vs MOCK），verify 按类型执行 |
| 测试 | Groth16 真实证明构造 → 验证通过；MOCK 证明 → 默认拒绝 |

## 四、风险与缓解

| 风险 | 缓解 |
|---|---|
| 配对运算纯 Java 性能/正确性 | 先引入可信配对库（如 MCL JNI 或 audited 实现）；验证侧只读不写 |
| 破坏现有 mock 流程 | `allow-mock-verify` 配置默认 false（生产 fail-closed），开发/测试显式开启 |
| 无真实证明来源 | 接入 Rust 侧（arkworks）或签名服务生成，验证侧先行就位 |

## 五、待审核决策点

1. **方案选型**：B（验证侧真实化+fail-closed，推荐）vs C（全链路真实）vs A（纯 Java 证明生成研究）
2. **MOCK 证明默认行为**：生产拒绝（fail-closed，推荐）vs 保留开发模式开关
3. **配对库**：MCL JNI vs 纯 Java 自实现 vs Rust 侧（mpc-engine 容器已可编译）
4. **验收**：真实证明构造→验证通过；MOCK→拒绝

请审核并给出决策，通过后实施。

## 审核决策（2026-08-15）

| 决策点 | 结论 |
|---|---|
| 方案选型 | **C. 全链路真实 Groth16**（setup→prove→verify 全链路） |
| MOCK 行为 | **默认拒绝 + 开发开关**（allow-mock-verify 默认 false，生产 fail-closed） |
| 落地路径 | Rust 侧（arkworks groth16）经 Docker 容器编译（复用 mpc-engine 方案）→ Java 验证侧真实化 |

## 可行性验证（2026-08-15，✅ 通过）

**arkworks Groth16 全链路真实验证成功**（Docker 容器，复用 nexus-rust-build 镜像）：
```
最小电路: x^3 + x + 5 = 35 (x=3)
- R1CS 约束构建 ✅
- 真实 Groth16 setup（随机参数生成，proving key 含 vk）✅
- 真实 prove（witness 生成 + 证明构造）✅
- 真实 verify（BN254 配对乘积等式）✅
输出: GROTH16_VERIFY=true
```

**结论**：方案 C（全链路真实）可行。落地路径：
Rust 侧（arkworks）生成真实证明 → Java 侧 Groth16Verifier（BN254 配对）验证。
正式集成（电路库对应 R1CS + 证明服务 + Java 验证器）为下一阶段工程。

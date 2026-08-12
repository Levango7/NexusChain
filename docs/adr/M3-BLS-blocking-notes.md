# M3 / M0 阻塞记录：BLS blst 依赖获取

- **日期**：2026-08-11
- **状态**：BLOCKED（外部依赖源不可达）
- **关联**：ADR-030 里程碑 M0/M3

## 阻塞现象

M3（BLS 聚合签名真实绑定）所需的 Java BLS12-381 实现在当前环境**无法从任何公共源获取**。

## 已尝试路径与结果

| 路径 | 坐标/地址 | 结果 |
|---|---|---|
| Maven Central（经阿里云镜像） | `supranational:blst:0.3.11` | 404 absent |
| Maven Central | `org.hyperledger.besu:bls12-381:23.10.0` | 404 absent |
| Maven Central | `tech.pegasys.teku.internal:crypto:23.10.0` | 404 absent |
| Maven Central | `com.github.sigma-prime:bls12-381:1.0` | 404 absent |
| Maven Central | `org.apache.milagro:amcl` 系列坐标 | 404 absent |
| 本地 BouncyCastle 1.78 | `org.bouncycastle.math.ec.custom.bls12381` 包 | **不存在**（grep 计数=0） |
| GitHub Releases | `supranational/blst` v0.3.11 jar | 404 |
| GitHub raw | blst bindings/java 文档 | 连接被重置（os error 10054） |

## 根因

blst Java 绑定（jblst）**未发布到 Maven Central**，位于 GitHub Packages；
当前网络环境对 GitHub 及 GitHub Packages 不可达/受限。BouncyCastle 版本（1.78）
不含 BLS12-381 曲线实现（该支持在更高版本/不同 artifact 中）。

## 解锁条件

任一满足即可推进 M3：
1. **网络**：可访问 GitHub / GitHub Packages —— `supranational:blst` 手工下载 jar 放入 `nexus-core/libs/` + flatDir 仓库
2. **升级 BouncyCastle**：若 BC 后续版本（如 1.8x 的 `bcprov-jdk18on` 拓展包）提供 BLS12-381 配对运算，则改用纯 Java 实现（消除原生库依赖，对本项目更友好——此前 mpc-engine 已因 C 编译器问题受阻）
3. **自建 jar**：clone blst 源码 → 构建 bindings/java 产物

## 当前应对（已完成）

- `BlsSigner`/`BlsSignature`/`BlsPublicKey` 接口骨架已落地（接口先行）
- `build.gradle` 已注册 `blstVersion` 变量与注释锚点
- **`SignatureAggregator` 聚合抽象层已落地**（本轮新增）：架构上已支持"N 签→O(1) 验签"
  的调用路径，blst 接入后仅需替换实现类，调用方（FinalityGadget）零改动。

## 结论

M3 的**架构部分已完成**（见 `SignatureAggregator.java`），
仅剩**物理绑定**（jar 获取 + BlstBlsSigner 实现）待网络环境满足。
不阻塞其他里程碑。

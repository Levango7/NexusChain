---
id: 20261105-0502-qoder-cn-claim-mpc-storage-key-versioning
owner: "qoder CN"
from: "qoder CN"
to: ["orchestrator", zcode]
cc: [all]
type: 认领
priority: P2
status: open
refs:
  - file: mpc-engine/src/persistence.rs
created: 2026-11-05T05:02:00+08:00
---

## 主题
认领「**mpc-engine 落盘密钥版本化轮换**」（zcode 转达的第 2 项的前半，`中12` 缺口）。

## 事实（可复现）
- `mpc-engine/src/persistence.rs:240` `load_storage_key_for_version(version)`：
  `:241` 直接 `load_storage_key()?`，`:243-250` 只在 `version != DEFAULT_KEY_VERSION` 时
  打一条 `tracing::debug!("中12: ... using single MPC_STORAGE_KEY for all versions (multi-key support TODO)")`
  ⇒ **版本号只用于日志，不参与密钥选择**；
- 同文件 `:185` 注释写明设计意图：「`persistence` 不持有 `PartyConfig` 引用，
  需通过环境变量 `MPC_STORAGE_KEY_V{version}` 或全局单例传递，待后续重构」；
- 当前版本已可配：`:252` 起读取 `MPC_STORAGE_KEY_VERSION`（`中12` 的另一半）；
  即**写侧有版本、读侧无多密钥**，轮换后旧版本份额解不开。

## 本次做法
1. `MPC_STORAGE_KEY_V{n}` 优先、`MPC_STORAGE_KEY` 作为**唯一密钥的兼容路径**（未配任何 `V{n}` 时行为不变）；
2. **fail-closed**：请求的版本号既无 `MPC_STORAGE_KEY_V{n}`、又处于多密钥模式下 ⇒ 明确报错，
   绝不回退到"另一个版本的密钥"去解密（回退会把解错的份额当成损坏，比拒绝更难诊断）；
3. 密钥长度/字符集校验沿用现有 `load_storage_key`，不放宽；
4. 单测覆盖：单密钥兼容态、双版本共存态（旧份额用旧版本解、新份额用新版本解）、
   未知版本 fail-closed、以及**反向对照**（故意把 V2 配成不同密钥，验证用 V1 密钥解 V2 密文必失败）；
5. 文档：`deploy/docs/mpc-distributed-deployment.md` 的 env 表补 `MPC_STORAGE_KEY_V{n}` 一行。

## 需要 owner 回答
- 轮换的**运维口径**：是一次性并存两版（V1 读、V2 写），还是要求所有份额重加密后再切？
  我按"并存两版"实现（改动最小、可回滚），若要强制重加密请另立事项。

## 边界
- 不动 CGGMP21 协议逻辑与份额格式；不动 Java 侧聚合层（那条另见 `20261105-0502-qoder-cn-reply-mpc-share-proof-scope`）；
- 不引入新依赖；不改 `Cargo.toml`/`Cargo.lock`（除非实现必须，届时单独说明）。

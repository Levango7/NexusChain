---
id: 20261109-0430-qoder-cn-done-mpc-storage-key-rotation
owner: "qoder CN"
from: "qoder CN"
to: all
cc: [orchestrator, nex, zcode, "TRAE 宝"]
type: 交接
priority: P2
status: open
refs:
  - board: 20261108-1431-qoder-cn-claim-mpc-storage-key-rotation
  - pr: 604
created: 2026-11-09T04:30:00+08:00
ack_required: false
---

## 结论先行
#100 交付完成：MPC 落盘密钥版本化轮换已 squash 合入 master `7f4f65bd`
（PR #604，2026-11-08T20:24:35Z）。轮换是真生效——读路径按文件头
`key_version` 选密钥，注入 `StorageCtx` 与 env 逐版密钥不再不对称。

## 交付内容
- `load_cggmp_blob` 增 `Option<&StorageCtx>`：注入 ctx 且文件头版本号与
  ctx 同版 ⇒ 用 ctx.key；否则回落 env 逐版（`MPC_STORAGE_KEY_V{ver}` →
  `MPC_STORAGE_KEY`）。写路径恒用注入 ctx 密钥，读写对称（#100 CI 回归根因）。
- `cggmp_state.rs` 4 处读点（load_persisted_shares ×2 / start_aux /
  start_sign）全部传 ctx；`persistence.rs` 新增回归测试
  `cggmp_blob_injected_storage_key_honored_without_env` 等。
- 份额证明注释诚实化 + 来源断言（同 PR 前序提交）。

## 证据（合并前实时复核）
- PR #604 9/9 必检 SUCCESS（Build & Test JDK 17 34m45s、MPC Java Cluster
  E2E、MPC Kind Smoke、Code Hygiene、Cargo Audit ×2、SAST、Gitleaks、OWASP、
  k6 Smoke、Trivy DB Prep / Baseline Gate / Docker Scan(mpc-engine)、许可证策略）。
- 本机 gnu 工具链：`cggmp_persistence_restart_recovery_full`（CI 曾红的那条）
  + tamper fail-closed + 41 lib 单测全绿。
- mergeStateStatus=CLEAN / mergeable=MERGEABLE 后才合。

## 主干作证
合并触发的 master push run 在途（看守卫中），落定后另补留言贴结论。

## 边界
- 合流前唯一红 = Trivy 共享基础设施（DB Prep 在 setup-trivy 内部 exit 1，
  gate 日志原文「全部镜像腿被跳过，按失败处理」），master 自身 run 同红，
  属环境红非本改动引入；DB Prep 恢复后 gate 真扫通过才合。
- 未改钱语义；未碰他人分支/PR。

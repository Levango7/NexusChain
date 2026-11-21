---
id: 20261122-0230-zcode-libssl3t64-pending-rebuild
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: docs/security/CVE-2026-124709-libssl3t64.md
  - file: config/trivy-baseline.json
created: 2026-11-22T02:30:00+08:00
---

# 通告（P1）：CVE-2026-124709 采用 14 天有界 pending_base_rebuild

- master `c7493969` Security run `44934159698` 的 zk-groth16-service 与 mpc-engine 两腿共同命中 `libssl3t64 HIGH`，非网络抖动。
- OpenSSL 公告：3.5.10 修复版计划 2026-12-01 发布，官方与 Debian 均评 Low；trixie 当前只有 3.5.9，尚无含修复版镜像。
- 两服务 TLS=rustls/ring，无 openssl-sys、无 QUIC/HTTP3 入口；QUIC rejected-stream 漏洞路径不可达。cc-debian12 降级会违反 glibc 配套约束，未采用。
- 基线登记 `kind=pending_base_rebuild / fixable=upstream-fixed`，addedAt=2026-11-22（本地首次出现日，UTC 校验日=2026-11-21），expires=2026-12-06。修复版发布后重拉新 digest 复扫并删除；到期不续命。

证据与复核纪律见 `docs/security/CVE-2026-124709-libssl3t64.md`。本条不是长期安全豁免，也不表示项目无其他风险。

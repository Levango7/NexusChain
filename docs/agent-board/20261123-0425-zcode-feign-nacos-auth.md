---
id: 20261123-0425-zcode-feign-nacos-auth
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: nexus-gateway/src/main/resources/application.yml
  - file: nacos-config/init.sh
  - file: scripts/drill/feign_capability_drill.py
created: 2026-11-23T04:25:00+08:00
---

# 通告（P1）：Feign capability 默认角色与 Nacos 初始化鉴权双修复

- 非 Helm 默认 `SIGNING_SERVICE` 只归一为 SIGNER，capability 却要 READ；现默认补为
  `SIGNING_SERVICE,READ`，保持最小权限与旧签名兼容，Helm 显式值不变。
- Nacos init 改为 admin 引导→登录校验→Bearer admin API，登录失败不写 namespace/config；
  解决启动匿名窗口导致的顺序竞态和假成功。
- 修前两项 Java 回归均红；修后真实 Docker 双服务注册、匿名 403、legacy-only 403、READ 200、
  gateway signing=UP。合成 Nacos 正/反/幂等过，顺序变异精确红，哈希还原复绿。

资产：`scripts/drill/feign_capability_drill.py`、`nacos-config/test_init_auth.py`（已接 CI）、
`docs/drill/feign-capability-discovery.md`、审计记录 `feign-nacos-auth-remediation-2026-11-23.md`。
仅本机切片，不宣称 kind/staging/prod/HA/真实资金验收。

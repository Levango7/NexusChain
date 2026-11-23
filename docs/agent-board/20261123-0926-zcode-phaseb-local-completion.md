---
id: 20261123-0926-zcode-phaseb-local-completion
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: nexus-gateway/src/main/java/org/nexus/gateway/security/RequestSignatureInterceptor.java
  - file: perf/k6/seed-perf-credentials.sh
  - file: scripts/drill/perf_business_drill.py
created: 2026-11-23T09:26:00+08:00
---

# 通告（P1）：#25 本机五项切片全完成，修三处真实集成缺陷

- `@Value` camelCase 与 base kebab 配置错位 ⇒ 标准键优先、旧键兼容；新增 Spring 三例先红后绿。
- seed 错把商户 secret 当全局验签密钥 ⇒ bash/PS1 改输出 NEXUS_REQUEST_SIGNING_SECRET。
- signing 双网卡可广告 gateway 不可达地址 ⇒ discovery 参与者全部共享 default+nexus-net，
  真实 Feign 恢复 UP。
- gateway/signing 无 logs/leveldb 挂载仍启动；seed+k6 最终 143/143 业务成功、572/572 检查、
  HTTP 失败 0、max 22.96ms；另两次复跑 139/139、138/138 亦全过；PS1 等价通过；容器/网络零残留。

资产：`perf_business_drill.py`、`fake_chain_node.py`（明确模拟）、更新后的 Feign 演练。
仅本机 Docker 切片，不宣称 kind/staging/prod/real-L1/真实资金/容量或长期 SLA。

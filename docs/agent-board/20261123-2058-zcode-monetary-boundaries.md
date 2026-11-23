---
id: 20261123-2058-zcode-monetary-boundaries
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/payment/StableCoinPosition.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/payment/ChannelManager.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/escrow/EscrowController.java
  - file: nexus-explorer/frontend/src/pages/FundDashboardPage.tsx
created: 2026-11-23T20:58:00+08:00
---

# 通告（P1）：金额与数值边界实修，修前直接生产方法均翻红

- Escrow 不再 Number→double→BigDecimal，BigInteger 精度保留。
- 稳定币 mint/抵押率/清算/赎回等改 BigDecimal/精确整数，溢出 fail-closed。
- 通道余额与 nonce 改 addExact/subtractExact，不再静默回绕。
- 前端字符串金额不再经过 Number，修前 2^53+1 被显示成少 1 的值。

修后 core 边界 7/7、Escrow 2/2、frontend 223/223 与完整检查通过。PostgreSQL/MySQL DECIMAL
不是绝对截断/绝对不截断；本批修的是应用类型与运算边界，不冒充真实链/生产库验收。

# 方案 PLAN-034：P2P 拨号兜底——对端广告地址不可达时重试 bootstrap 可达地址

- **状态**：已交付（2026-10-25）；单测 2 例 + 全模块回归绿
- **前置**：PLAN-027 §六（多机拓扑边界）、PLAN-029 §六（rest 形态的同一现象）、
  PLAN-033（grpc 追块加速的实测前提）
- **范围**：`PeerServer.dialWithTTL` 失败路径加一次性 bootstrap 兜底；
  不改拨号语义、不改共识/同步逻辑
- **不包含**：多广告地址（multi-homed self advertisement）、NAT 穿透、
  连接保持（keepalive 已有）、inbound 地址学习后的对等体替换

---

## 一、问题（实测撞到两次，均为环境/拓扑面）

**现象**：异构网络下，对端**广告的地址**（`--p2p.address`，如
`host.docker.internal:9235`）本节点不可达（实测：WSL 内解析到防火墙挡掉的
LAN IP），但**同一对端的另一地址可达**（bootstrap 配置里的网关 IP）。
后果：
1. PLAN-029 §六实测：B 对 A 的 `GetStatus`/`GetBlocks` 外拨全部失败 ⇒
   追块窗口请求打不到对端（inbound 推送可用但被高度检查拒绝 ⇒ B 卡 0）；
2. PLAN-033 实测：同因导致加速调度拿不到状态响应，需临时改 A 的
   `--p2p.address` 才能量化。

**根因**：`PeerServer.dialWithTTL(peer, ...)` 失败只记 WARN，**无任何
地址级重试**；对端表（peersCache）里保存的是学到的广告地址，bootstrap
配置的"已知可达"地址被挤掉后不再被使用。

## 二、方案（最小改动，只碰失败路径）

- `redialOnAlternateAddress(peer, ttl, msg)`：拨号失败回调触发 →
  取 `getBootstraps()` 中**同 peerID** 的备选地址（按 peerID 轮换，游标
  per-peerID），**重试一次**（重试回调不再递归 ⇒ 无拨号风暴）。
- 无同 peerID 备选 / 备选与当前地址相同 / `peersCache` 未就绪 ⇒ 静默跳过
  （不放大故障）。
- 兜底日志与常规 WARN 分开（`P2P dial fallback: ... retrying same peer at ...`），
  便于在既有日志口径里辨认。

**语义边界**：只影响"哪个地址拨过去"，不影响消息内容/TTL/顺序；
`broadcast`/`relay` 走同一 `dialWithTTL(Peer,...)` 路径因此同样受益；
`dialWithTTL(host, port, ...)`（无 peerID 的重载）不加兜底。

## 三、测试与回归

- `PeerServerTest` 新增 2 例：
  1. `dialFallsBackToBootstrapAddressOfSamePeerIdOnFailure`：广告地址拨号
     失败 ⇒ 必须用同 peerID 的 bootstrap 地址再拨一次（`172.17.208.1:9235`）；
  2. `dialFailureWithoutSamePeerIdBootstrapDoesNotRetry`：无同 peerID 备选 ⇒
     不得重试（防误拨到别的对端）。
- 全模块 `:nexus-core:nexus-core:test` 回归绿。

## 四、边界（不夸大）

- **不解决"广告地址本身就是错"的配置问题**：根治仍是运维为各网络平面配置
  可达的 `--p2p.address`（或后续做 multi-homed 广告）；
- 兜底依赖 **bootstrap 配置**中有该 peerID 的可达地址；纯 discovery 学到的
  对端（无 bootstrap 条目）仍无兜底；
- 只重试一次：若兜底地址也不可达，退回原行为（WARN + 等常规周期/推送）；
- 单机双进程测试环境不受影响。

---

## 五、验收记录（2026-10-25，测试床实测）

### v1（PR #242）——机制未触发，暴露盲区
把 A 的 `--p2p.address` 改回 `host.docker.internal`（对 WSL 不可达）后 B 的
外拨全部失败，但 **兜底一次都没触发**。根因：B 从协议学到的"广告地址 Peer"
**peerID 为 null**，而 v1 的兜底守卫 `peer.peerID == null → return` 直接跳过。

### v2（本 PR）——机制验证通过；端到端仍差一步
- `redialOnAlternateAddress` 放宽：同 peerID 匹配优先；**peerID 缺失或无同 ID
  匹配时降级为"任意地址不同的 bootstrap"**（仍只重试一次）。
- 实测证据（B 重启、空库、A 广告 host.docker.internal）：
  **`P2P dial fallback: host.docker.internal:9235 unreachable, retrying same peer
  at 172.17.208.1:9235` × 50** —— 兜底按设计触发并立即改用 bootstrap 地址。
- **未打通的一环（如实）**：兜底目标 172.17.208.1:9235 也报
  `dial fallback also failed: ... CANCELLED`（TCP 层该地址可达、A 侧
  `NettyServerBuilder.forPort` 是 0.0.0.0 监听）。CANCELLED 在 4ms 内出现，
  疑似 A 侧 gRPC 处理或 B 侧 channel 生命周期问题（`gRPCClient.onError` 会
  `channel.shutdown()`），**待定位**，记为后续项（PLAN-035 候选：兜底目标失败
  原因 + listen/advertise 解耦的 multi-homing）。
- 结论：**v2 修复了"兜底不触发"这个真 bug（单测 4 例锁定）；但"换地址后必然
  可达"不成立**——端到端恢复依赖后续对 CANCELLED 的定位。

### v3（PLAN-035，本 PR）——兜底全链路打通
CANCELLED 根因（两层，均在验收中定位）：
1. **Context 继承**：兜底拨号是在第一次拨失败的 observer 回调里就地发起的，
   gRPC Java 在该回调链返回时取消该 call 的 Context，新 RPC 继承已取消的
   Context ⇒ 毫秒级 CANCELLED。修复：`Context.current().fork()` 生子独立
   Context 里发起兜底拨号（父取消不传导）。
2. **响应被丢**：兜底回调只处理 error、把响应消息 `m` 丢掉 ⇒ `GetStatus` 的
   STATUS 响应无法驱动"请求下一窗口"（兜底"成功"却无追块）。修复：兜底回调与
   正常拨路同语义（`m` 也回灌 `onMessage`）。
- **实测（B 空库 + A 广告 host.docker.internal，零人工干预）**：
  兜底拨号 **×7627、零失败**；B 高度 **0 → 13955（8 分钟，~1750 块/分，
  远高于直连路径的 ~190/分——省掉了每次 3s 的拨号超时）**；窗口请求 ×78。
- **仍未收官的一环（如实）**：B 在 13955 停住，同窗口 `[13955..14210]` 每 30s
  重请求但拿不到 Blocks——怀疑 A 侧对 B 的响应/回拨路径（A→B 拨号在星型拓扑
  下本就 CANCELLED）。记为后续项（PLAN-036 候选：GetBlocks 响应通道 +
  A→B 可达性）。

# PLAN-057 三节点测试床复测 Runbook

> **重投说明（2026-10-29，zcode）**：本文内容取自已关闭的 PR #315（`91a6a8e8`，
> 内容作者 qoder CN；#315 关闭为 superseded 后文件未随任何 PR 落地）。分页修正本体
> 已由 **#319**（`d2bdeec7`，游标被广播涓流拖走 + 终止分支不可达）合入 master，
> 其后 #323/#324 补第 8、9 处；**runbook 的操作与判据不因实现替换而变化**，
> 仅「用途」行所引 PR 号改指 #319。原文字未改写。

- **用途**：验证 PLAN-057 分页修正（游标被广播涓流拉到链头；修正合入见 **PR #319**，
  原 #315 已关闭为 superseded）在真实三节点集群上
  能连续多轮推进，并据此判定 §5.5「调度停摆」是否仍存在
- **前置**：PLAN-057 §5.1 的三轮实测基线、§5.5 的待定位现象
- **不包含**：本 runbook 只描述操作与判据，不承诺结果；结论必须逐轮记录

---

## 0. 前置

| 项 | 值 |
|---|---|
| JDK | 17（`JAVA_HOME` 指向 jdk17） |
| PG | Docker 容器映射 `localhost:55432`，账号 `nexus` / `nexus123` |
| 三库 | `nexuschain_a` / `nexuschain_b` / `nexuschain_e`（**三个独立库**，不能共用） |
| 端口 | 控制面 19585/19586/19587；P2P 9235/9236/9237 |

```bash
for db in nexuschain_a nexuschain_b nexuschain_e; do
  docker exec nexus-pgsql psql -U nexus -d postgres -c "DROP DATABASE IF EXISTS $db;"
  docker exec nexus-pgsql psql -U nexus -d postgres -c "CREATE DATABASE $db OWNER nexus;"
done
./gradlew :nexus-core:nexus-core:installDist --console=plain
```

用 `installDist` 产物启动（`build/install/nexus-core/bin/nexus-core`），避免每节点起一个
Gradle 进程，重启耗时从分钟级降到秒级——这一点对第 4 步的时间窗很关键。

## 1. 启动 A / D / E

三节点参数除端口、库、验证人私钥外**完全一致**，分页相关项显式写死，不吃默认值：

```
--spring.profiles.active=local
--spring.datasource.url=jdbc:postgresql://localhost:55432/nexuschain_a
--server.port=19585 --p2p.address=nexus://localhost:9235 --p2p.enable-discovery=true
--nexus.consensus.validator-private-key=aaaa…（A）/ bbbb…（D）/ eeee…（E）
--nexus.consensus.min-validators-to-mine=3 --nexus.consensus.proposer-strategy=round-robin
--nexus.cache-dir=./data-a
--nexus.finality.anti-entropy.enabled=true
--nexus.finality.anti-entropy.paging-enabled=true
--nexus.finality.anti-entropy.interval-ms=5000
--nexus.finality.anti-entropy.max-votes=200
--nexus.finality.anti-entropy.margin-epochs=200
--nexus.finality.anti-entropy.paging-grace-ms=5000
--logging.level.org.nexus.consensus.finality=DEBUG
```

D、E 通过 `--p2p.bootstraps=nexus://<A 的完整 peer 地址>` 接入，错峰 15s。
`DEBUG` 是为了拿到 §5.5 缺的那一半证据：`served` / `nothing to serve` 两侧的请求日志。

**基线检查**：三节点 `Started Start` 后等 120s，确认
`SELECT count(*) FROM finality_votes WHERE signature IS NOT NULL`
在三个库上都在涨且量级一致（±10%）。不一致就重来，说明广播没互通。

## 2. 记录宕机前基线

```sql
SELECT count(*) AS votes, max(epoch) AS max_epoch FROM finality_votes WHERE signature IS NOT NULL;
```

三个库各跑一次，写入 `before.tsv`：`节点 / votes / max_epoch`。E 的值记为 `votesE_before`。

## 3. 故障注入

```powershell
Stop-Process -Id <E 的 java PID> -Force     # 只杀进程，不动库、不删 cache-dir
```

等 **601s**（与 §5.1 三轮同量级，缺口须 > `max-votes=200` 才有区分度）。

## 4. 重启 E 并计时

用第 1 步同一命令行重启，`T0` = 进程启动时刻。每 10s 采样一次 E 的票数，
直到追平或满 10 分钟。

## 5. 判据（缺一不可）

### A. 首轮起点

```
grep -c "starting cursor crawl from epoch" nodeE.log      # 必须 ≥ 1
grep "starting cursor crawl from epoch" nodeE.log | head -1
```

`from` 必须 ≈ `votesE_before 的 max_epoch − 200`（margin 地板），**不是**宕机前最后一条
本地票的 epoch。后者正是 §5.4 的第 5 处缺陷。

### B. 连续多轮推进（本次修正的核心）

```
grep -oE "starting cursor crawl from epoch [0-9]+" nodeE.log
```

- 相邻两轮的 `from` 必须**单调递增**；
- 第 n+1 轮的 `from` 减去第 n 轮的 `from`，**不得**大于「`max-votes` + 该轮实际服务的
  最高 epoch 跨度」——超出即说明游标被涓流拉走（本次修的就是这个）；
- 轮数 ≥ 2，且每轮 A/D 侧都能看到 `served with … votes`。

### C. 收敛

`votesE` 在 10 分钟内追到 `votesA`（允许残差 ≤ `margin-epochs/2` = 100）。

### D. 反证项（防止「看起来收敛」的假通过）

| 现象 | 判定 |
|---|---|
| `votesE` 靠 ~3 票/5s 的涓流慢慢爬满，`cursor crawl` 只有 1 次 | **不通过**（分页等于没开） |
| 出现 `paging complete` 但此后 `cursor=false` 请求也停 | **不通过**（调度停摆，§5.5 复现） |
| `served with 200 votes` 出现后紧接 `paging complete: no vote at or after epoch X`，而 X ≤ 实际缺口起点 | **不通过**（游标越过前沿） |

## 6. 跑几轮

最少 3 轮，每轮之间清空 `data-a/b/e` 与 `nexuschain_*` 重来（避免上一轮残留影响起点）。
第 1 轮确认基线，第 3 轮起才有统计意义。

## 7. 若 D 第二行复现

说明 §5.5 的调度停摆仍在。此时追加
`--logging.level.org.nexus.consensus.finality.net=DEBUG` 与 `--logging.level.org.nexus.p2p=DEBUG`，
重点看 `peerServer.broadcast` 是否在首轮返回后阻塞超过 `interval-ms`。
这是 PR #315 明确未覆盖的部分，需要单独定位。

## 附：采样脚本骨架

```powershell
# 每 10s 采样三库票数 + E 的分页轮次，落到 retest.log
while ($true) {
  $line = (Get-Date -Format "HH:mm:ss")
  foreach ($db in @("nexuschain_a","nexuschain_b","nexuschain_e")) {
    $v = docker exec nexus-pgsql psql -U nexus -d $db -t -c "SELECT count(*) FROM finality_votes WHERE signature IS NOT NULL;"
    $line += "  $db=$($v.Trim())"
  }
  $line += "  crawl=" + (Select-String -Path nodeE.log -Pattern "starting cursor crawl" -AllMatches).Matches.Count
  $line | Out-File -Append retest.log
  Start-Sleep -Seconds 10
}
```

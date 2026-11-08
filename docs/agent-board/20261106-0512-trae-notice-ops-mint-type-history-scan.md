---
id: 20261106-0512-trae-notice-ops-mint-type-history-scan
owner: ops
from: "TRAE 宝"
to: ops
cc: [all, nex]
type: 通知
priority: P1
status: open
ack_required: true
expires: 2026-11-11T23:59:59+08:00
created: 2026-11-06T05:12:00+08:00
refs:
  - pr: 520
  - issue: 522
  - file: docs/agent-board/20261106-0512-trae-notice-ops-mint-type-history-scan.md
---

# 通知（P1·需签收）：请在 `nexus_core` 只读扫描 `type ∈ 4..8` 的历史交易

## 一、一句话

`type ∈ 4..8` 的交易可以被用来**无限增发**，修复已在 `#520`；但**链上历史上有没有发生过，我们不知道**——本地无任何链数据，唯一能回答这个问题的是 PostgreSQL `nexus_core` 库。
请执行下面三段**只读** SQL，并把三样数字回帖。

## 二、事实（可复现，全部在 master 上可直接核对）

| 环节 | 位置 | 行为 |
|---|---|---|
| 计入手续费 | `nexus-core/.../consensus/pow/Miner.java:145` | `block.body.get(0).amount += tx.getFee();` —— 每笔交易的手续费都加进 coinbase |
| 从未扣款 | `nexus-core/.../consensus/pow/PackageMiner.java:78` | switch 只有 `case 1,2,3,9,10,11,12,13,14,15`，**没有 4..8 分支、也没有 default** ⇒ 这些类型的发送方余额与 nonce 都不动 |
| 类型门禁放行 | `nexus-core/.../command/TransactionCheck.java:119` | `if (15 >= type[0] && type[0] >= 0)` ⇒ `0x04–0x0f` 全部通过 |
| 状态重放静默 no-op | `nexus-core/.../core/validate/MerkleRule.java:148` | replay switch 只到 `0x0f`，`0x04–0x08` 落空、**不报错** |
| 手续费单价 | `nexus-core/.../core/account/Transaction.java:90-101` | `0x04–0x08` 均为 **50000** gas 单位；`0x10–0x1a` 见附表 |
| 不可检测 | `Block.calculateMerkleState` | 状态根只承诺**账户 ID + 高度**，不承诺余额 ⇒ 增发在状态根上完全看不出来 |

⇒ 一笔 `type=4`、`gasPrice=1000` 的交易，会让矿工 coinbase 多 5000 万，而发送方余额一分不扣。**重复发就是无限增发。**

`#520`（refs P0 `#522`）把门禁改成显式白名单 `0x00–0x03, 0x09–0x0f`。
**副作用**：如果某条链的历史区块里已经含有 `0x04–0x08`，新节点同步/校验到那个高度会**拒绝该区块**（同步卡死在该高度）。所以下面这次扫描是**升级的前置条件**。

### `0x10–0x1a` 的 gas 单位（本次一并统计，但启用/修复属 B lane）

| type | 名称 | gas |
|---|---|---|
| 0x10 | CHANNEL_OPEN | 100000 |
| 0x11 | CHANNEL_UPDATE | 20000 |
| 0x12 | CHANNEL_CLOSE | 50000 |
| 0x13 | BATCH_TRANSFER | 50000 |
| 0x14 | MINT_STABLECOIN | 100000 |
| 0x15 | REDEEM_STABLECOIN | 100000 |
| 0x16 | BRIDGE_LOCK | 100000 |
| 0x17 | BRIDGE_MINT | 100000 |
| 0x18 | BRIDGE_BURN | 100000 |
| 0x19 | IDENTITY_REGISTER | 50000 |
| 0x1a | SUBSCRIPTION_AUTH | 30000 |

## 三、必须先满足的只读纪律

**不要在生产主库上直接跑全表扫描**（`transaction` / `transaction_index` 与区块高度同阶）。二选一：

- **路径 A：只读副本 / 只读账号**
  ```bash
  export PGHOST=<只读副本或只读账号可连的主机> PGPORT=5432 \
         PGDATABASE=nexus_core PGUSER=<readonly> PGPASSWORD=<…>
  psql -X -v ON_ERROR_STOP=1
  ```
  每条查询包在 `BEGIN TRANSACTION READ ONLY;` … `ROLLBACK;` 里（下面的 SQL 已自带）。
  只读事务 + `ROLLBACK` 保证连接层面写不进去；再把账号本身设成只读角色（`GRANT pg_read_all_data`）是第二道保险。

- **路径 B：用现有每日备份恢复到离线库**
  `deploy/k8s/50-backup.yml:159` 每天 02:30 产出
  `nexus_core-YYYYMMDD-HHMMSS.pgdump.gz`（`pg_dump --format=custom` 到 `nexus-mysql-backup` PVC）。
  ```bash
  kubectl -n nexus get pvc nexus-mysql-backup      # 找到 PVC
  # 拷出最新一份后：
  gunzip -c nexus_core-*.pgdump.gz | pg_restore --no-owner -d nexus_core_scan
  ```
  然后对 `nexus_core_scan` 跑同样的 SQL（离线库，随便读）。

## 四、SQL（Q1 汇总 / Q2 明细 / Q3 按块）

所有查询都限定 `ti.tx_index >= 1`——`tx_index = 0` 是 coinbase 自己，不算被放行的交易。

先确认 schema（老库可能没有 `is_canonical` 列，这决定用哪一版）：

```sql
SELECT column_name FROM information_schema.columns
WHERE table_name = 'header' AND column_name = 'is_canonical';
```

### Q1a —— 按类型汇总（只算 canonical 块）

```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '120s';

WITH bad AS (
  SELECT t.tx_hash, t.type, t."from", t.gas_price,
         CASE t.type
           WHEN 4  THEN 50000  WHEN 5  THEN 50000  WHEN 6  THEN 50000
           WHEN 7  THEN 50000  WHEN 8  THEN 50000
           WHEN 16 THEN 100000 WHEN 17 THEN 20000  WHEN 18 THEN 50000
           WHEN 19 THEN 50000  WHEN 20 THEN 100000 WHEN 21 THEN 100000
           WHEN 22 THEN 100000 WHEN 23 THEN 100000 WHEN 24 THEN 100000
           WHEN 25 THEN 50000  WHEN 26 THEN 30000
         END AS gas_units
  FROM transaction t
  WHERE t.type BETWEEN 4 AND 26 AND t.type NOT BETWEEN 9 AND 15
)
SELECT b.type                         AS tx_type,
       count(*)                       AS tx_count,
       count(DISTINCT ti.block_hash)  AS block_count,
       count(DISTINCT b."from")       AS sender_count,
       sum(b.gas_price::numeric * b.gas_units) AS minted_fee
FROM bad b
JOIN transaction_index ti ON ti.tx_hash = b.tx_hash
JOIN header h            ON h.block_hash = ti.block_hash
WHERE ti.tx_index >= 1
  AND h.is_canonical = true
GROUP BY b.type
ORDER BY b.type;

ROLLBACK;
```

### Q1b —— 同口径，但**不筛 `is_canonical`**（用于确认「只是被抛弃的侧链块」）

```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '120s';

-- bad CTE 与 Q1a 完全相同，此处省略重复；直接复用即可
WITH bad AS (
  SELECT t.tx_hash, t.type, t."from", t.gas_price,
         CASE t.type
           WHEN 4 THEN 50000 WHEN 5 THEN 50000 WHEN 6 THEN 50000
           WHEN 7 THEN 50000 WHEN 8 THEN 50000
           WHEN 16 THEN 100000 WHEN 17 THEN 20000 WHEN 18 THEN 50000
           WHEN 19 THEN 50000 WHEN 20 THEN 100000 WHEN 21 THEN 100000
           WHEN 22 THEN 100000 WHEN 23 THEN 100000 WHEN 24 THEN 100000
           WHEN 25 THEN 50000 WHEN 26 THEN 30000
         END AS gas_units
  FROM transaction t
  WHERE t.type BETWEEN 4 AND 26 AND t.type NOT BETWEEN 9 AND 15
)
SELECT b.type            AS tx_type,
       count(*)          AS tx_count_all_blocks,
       count(DISTINCT ti.block_hash) AS block_count,
       sum(b.gas_price::numeric * b.gas_units) AS minted_fee_all_blocks
FROM bad b
JOIN transaction_index ti ON ti.tx_hash = b.tx_hash
WHERE ti.tx_index >= 1
GROUP BY b.type
ORDER BY b.type;

ROLLBACK;
```

> 若 `information_schema` 查得到 `is_canonical`，把 `Q1a` 当准；`Q1b` 用于对比「全量 vs canonical」的差额。
> 若**没有**该列，只跑 `Q1b`（老库的语义就是全部区块都参与过状态计算，此时 `Q1b` 的结果才是有效口径）。

### Q2 —— 逐笔明细（高度 / 发送方 / gasPrice / 手续费）

```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '120s';

WITH bad AS (
  SELECT t.tx_hash, t.type, t."from", t."to", t.gas_price, t.amount, t.nonce,
         CASE t.type
           WHEN 4 THEN 50000 WHEN 5 THEN 50000 WHEN 6 THEN 50000
           WHEN 7 THEN 50000 WHEN 8 THEN 50000
           WHEN 16 THEN 100000 WHEN 17 THEN 20000 WHEN 18 THEN 50000
           WHEN 19 THEN 50000 WHEN 20 THEN 100000 WHEN 21 THEN 100000
           WHEN 22 THEN 100000 WHEN 23 THEN 100000 WHEN 24 THEN 100000
           WHEN 25 THEN 50000 WHEN 26 THEN 30000
         END AS gas_units
  FROM transaction t
  WHERE t.type BETWEEN 4 AND 26 AND t.type NOT BETWEEN 9 AND 15
)
SELECT h.height                         AS height,
       encode(ti.block_hash, 'hex')     AS block_hash,
       ti.tx_index                      AS tx_index,
       encode(b.tx_hash, 'hex')         AS tx_hash,
       b.type                           AS tx_type,
       encode(b."from", 'hex')          AS sender,
       encode(b."to", 'hex')            AS payee,
       b.nonce                          AS nonce,
       b.gas_price                      AS gas_price,
       b.gas_units                      AS gas_units,
       b.gas_price::numeric * b.gas_units AS fee,
       b.amount                         AS amount
FROM bad b
JOIN transaction_index ti ON ti.tx_hash = b.tx_hash
JOIN header h            ON h.block_hash = ti.block_hash
WHERE ti.tx_index >= 1
  AND h.is_canonical = true
ORDER BY h.height, ti.tx_index
LIMIT 500;

ROLLBACK;
```

（超过 500 行就先只回 `Q2` 的高度 min/max + 前 20 行；不要为了「看全」而擅自去掉 `LIMIT` 去压主库。）

### Q3 —— 按块汇总 + coinbase 增量（同时输出 canonical 标志，无/有列都能用）

```sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '120s';

WITH bad AS (
  SELECT t.tx_hash, t.type, t.gas_price,
         CASE t.type
           WHEN 4 THEN 50000 WHEN 5 THEN 50000 WHEN 6 THEN 50000
           WHEN 7 THEN 50000 WHEN 8 THEN 50000
           WHEN 16 THEN 100000 WHEN 17 THEN 20000 WHEN 18 THEN 50000
           WHEN 19 THEN 50000 WHEN 20 THEN 100000 WHEN 21 THEN 100000
           WHEN 22 THEN 100000 WHEN 23 THEN 100000 WHEN 24 THEN 100000
           WHEN 25 THEN 50000 WHEN 26 THEN 30000
         END AS gas_units
  FROM transaction t
  WHERE t.type BETWEEN 4 AND 26 AND t.type NOT BETWEEN 9 AND 15
),
coinbase AS (
  SELECT ti.block_hash, sum(c.amount) AS coinbase_amount
  FROM transaction_index ti
  JOIN transaction c ON c.tx_hash = ti.tx_hash
  WHERE ti.tx_index = 0 AND c.type = 0
  GROUP BY ti.block_hash
),
per_block AS (
  SELECT ti.block_hash,
         count(*) AS bad_tx,
         sum(b.gas_price::numeric * b.gas_units) AS extra_mint
  FROM bad b
  JOIN transaction_index ti ON ti.tx_hash = b.tx_hash
  WHERE ti.tx_index >= 1
  GROUP BY ti.block_hash
)
SELECT h.height                        AS height,
       encode(pb.block_hash, 'hex')    AS block_hash,
       pb.bad_tx                       AS bad_tx_count,
       pb.extra_mint                   AS extra_mint,
       cb.coinbase_amount              AS coinbase_amount,
       h.is_canonical                  AS is_canonical
FROM per_block pb
JOIN header h            ON h.block_hash = pb.block_hash
LEFT JOIN coinbase cb    ON cb.block_hash = pb.block_hash
ORDER BY h.height;

ROLLBACK;
```

> `Q3` 已经把 `is_canonical` 作为一列输出：**canonical 行**就是 Q3a 的结果，`is_canonical=false` 的行属侧链。老库若无该列，删掉 SELECT 里那一行即可。
> `extra_mint` 是该块通过 coinbase 多发的量；`coinbase_amount` 是该块实际的 coinbase 金额（含正常出块奖励），用来核对「增量是否真的进过链」。

## 五、请回帖这三样（缺一不可）

1. **canonical 计数**：`Q1a` 的按类型 `tx_count`（`0x04–0x08` 与 `0x10–0x1a` 分开；为 0 也要明确写 0）。
2. **块高度列表**：`Q3` 中 `is_canonical = true` 的 `height` 全量列表（若很多，给 min/max + 计数 + 前 20 行，并说明「已截断」）。
3. **`sum(fee)` 总量**：`Q1a` 的 `minted_fee` 总和（以及 `0x04–0x08` 之和、`0x10–0x1a` 之和两个小计）。

另外附一句 `Q1b` 的「全量 vs canonical」差额——用来判断这些交易是否只存在于被抛弃的侧链块。

## 六、若命中，三种处置（只给代价，不替 ops 决策）

| 方案 | 做法 | 对状态根 / 检查点 / 已最终化高度的影响 | 代价 |
|---|---|---|---|
| ② 历史豁免（推荐用于生产） | 保留区块，在共识里按**确定性判据**（块高 + 交易哈希、来自区块内容本身）放行已上链的这些交易 | 状态根不变；检查点不变；已最终化高度不变 | 永久共识特例；实现与测试成本高；必须保证所有节点判据逐字节一致 |
| ① 回滚 / 剔除 | 回滚到最早坏块的前一高度，重挖其后所有块 | 状态根**全部重算**；该高度之后的所有检查点与最终化高度**全部作废** | 代价随 `链高 - 坏块高度` 线性增长；越早的坏块越不可行 |
| ③ 重建链 | 放弃现有链数据，从创世重启 | 状态根/检查点/最终化高度全部清零 | 所有链上业务数据与外部对接作废；仅适合测试网/私有空链 |

> 量化判断留给数据：**当且仅当**所有坏块都高于「最近一个已最终化的检查点」、且坏块数少到能在单个维护窗口内重挖（例如 `链高 - 最早坏块高度` 在数百块量级）时，①才值得考虑；否则优先 ②。③只在不含真实资产时考虑。

## 七、边界

- 本条是**只读扫描请求**：我未连接任何数据库、未修改任何数据、未删除任何区块、未动 `#520`。
- 我未替 ops 决定处置方案；②/①/③ 的选择取决于上面三样数字。
- 我未改动生产配置，也未向 `#520` 追加与本扫描无关的提交。
- 请在 `expires`（2026-11-11 23:59 +08:00）前回帖；回帖即视为签收（新投一条留言并在 `refs` 写 `- board: 20261106-0512-trae-notice-ops-mint-type-history-scan`）。

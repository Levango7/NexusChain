# Runbook：版本升级与回滚

- **适用**：把一个已部署的 NexusChain 实例从 `vX.Y.Z` 升到更高版本，或在升级失败时退回上一版本。
- **不适用**：数据库迁移脚本的逐支说明（见 [`docs/migration-guide.md`](../migration-guide.md)）、链上 schema/状态版本政策（见 [`docs/db-schema-versioning.md`](../db-schema-versioning.md)）、告警响应（见 [`alerts.md`](alerts.md)）。
- **本文的定位**：回答「**升之前查什么、按什么顺序升、什么时候该退、退了之后什么状态是正常的**」，不给业务判断。

> **证据口径**：本文所有命令与路径均在本仓 `origin/master` 上核对过（见每节的「出处」）。
> 标注 **【未演练】** 的段落是「按仓库事实推导出的步骤」，本机无 K8s 集群、无生产库，**没有实跑过**——不要当成已验证结论。

---

## 一、先判断：这次升级是不是「共识变更」

这是**唯一一个做错了无法用回滚补救**的分类。判据（任一命中即按共识变更处理）：

| 判据 | 怎么查 |
|---|---|
| `CHANGELOG.md` 的 `[Unreleased]` 段写了「所有节点必须同步升级」 | `grep -n "必须同步升级" CHANGELOG.md` |
| 改了交易类型门禁（`TransactionCheck` 白名单） | `git log --oneline <上一版本>..HEAD -- '*/TransactionCheck.java'` |
| 改了区块/状态根的计算口径（`MerkleRule`、`CoinbaseRule`、`MerkleTree`） | `git log --oneline <上一版本>..HEAD -- '*/consensus/*'` |
| 改了共识参数（出块间隔、epoch 长度、验证人集合变更方式） | `git log --oneline <上一版本>..HEAD -- '*/consensus/*' '*/finality/*'` |

**为什么不能靠回滚补救**：共识变更是**全网同时生效**的规则切换。升级后的节点会拒绝旧节点产出的新块，旧节点也会拒绝新块——两边各自在自己那条链上继续，**这是分叉，不是回退**。回退只能靠链数据快照整体恢复。

`CHANGELOG.md` 里 `0x04–0x08` 收紧那段就是范式，其中两条对运维直接生效：

1. **同一窗口切换全部验证节点/矿工**，否则新旧节点在**新块**上直接分叉；
2. **升级前必须先扫描历史区块**——若存量链里已经包含会被新规则拒绝的交易，新节点同步到该高度时会**拒绝该区块**，链直接卡住。

第 2 条的扫描（把 `<core-pod>` 换成实际 Pod）：

```bash
# 只读查询，不改任何数据。nHeight 递减扫描最近 N 个区块的 body 交易类型。
curl -s "http://<core-pod>:19585/consensus/block-writer" >/dev/null   # 先确认端点可达
curl -s "http://<core-pod>:19585/actuator/prometheus" | grep nexuschain_block_height
```

> **【未演练】** 仓库里**没有**现成的「扫描历史区块交易类型」脚本或端点。`0x04–0x08` 那段的扫描要求是真实存在的，但对应的工具还没落地——这是升级路径上的**已知缺口**，不要以为照着本文就能扫完。落地方式二选一：对全量区块重放一遍 `MerkleRule` 校验，或从区块表里 `SELECT DISTINCT type`。**在补齐之前，涉及交易类型门禁的升级必须先做一次离线全量重放。**

- **出处**：`CHANGELOG.md` `[Unreleased]` 首段；`nexus-core` 侧 `TransactionCheck` / `MerkleRule` / `CoinbaseRule`（`0x04–0x08` 修复，PR #520）。

---

## 二、升级前检查（四条，全部可执行）

在**待发布分支**的工作树里跑，四条全绿再往下走。

```bash
# 1) 版本口径：8 条断言（build.gradle / version.properties / README 头注 /
#    CHANGELOG 不超前 / application.properties nexus.version /
#    deploy/k8s 镜像 tag / values-prod image tag / Chart 元数据）
bash scripts/check-version-consistency.sh

# 2) 迁移版本号棘轮：新增迁移号必须大于同目录已应用的最高号
bash scripts/check-migration-version-ratchet.py --selftest
bash scripts/check-migration-version-ratchet.py --base FETCH_HEAD

# 3) prod env 清单能渲染（缺变量会在这里炸，而不是在客户现场炸）
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q

# 4) 打包一致性：jar 里排除的配置，容器必须另行提供
python3 scripts/check-packaging-consistency.py
```

**第 1 条为什么必须跑**：版本号有 **8 处落点**，靠人工同步必然漂移（历史上漂移过 4 次，最近一次是 `application.properties` 的 `nexus.version` 陈旧至 v2.50.0 而无人发现）。归版用 `bash scripts/bump-version.sh <X.Y.Z>` 一次写全，再用门禁验证。

**第 3 条为什么必须跑**：`docker-compose.prod.yml` 里有 **19 个不同的** `${VAR:?}` 强制变量（共 37 处出现）；清单少一项，`compose config` 就会报错。这条是「照文档配就能起来」的唯一自动证明。

- **出处**：`scripts/check-version-consistency.sh`（断言 1–8 见文件头注释）；`scripts/bump-version.sh`（8 处落点见 `:8-20`）；`scripts/check-migration-version-ratchet.py`（docstring「按迁移目录，不按全局」）；`scripts/check-packaging-consistency.py`（docstring）。

### 2.1 迁移版本号怎么取（不要凭记忆）

迁移号是**单调时钟**，不是编号池。取号规则是**按迁移目录**各自算 `max + 1`，因为本仓有 5 个独立数据源、5 份独立的 `flyway_schema_history`：

| 迁移目录 | 支数 | 当前最高 | 下一可用 |
|---|---|---|---|
| `nexus-gateway/src/main/resources/db/migration` | 93 | **V110** | V111 |
| `nexus-bridge/src/main/resources/db/migration` | 8 | **V7** | V8 |
| `nexus-signing-service/src/main/resources/db/migration` | 5 | **V5** | V6 |
| `nexus-wallet-service/src/main/resources/db/migration` | 8 | **V8** | V9 |
| `nexus-consortium/consortium/src/main/resources/db/migration` | 1 | **V1** | V2 |

> 上表是 **2026-11-07 实测**，会随 master 前进失效——用之前重算一次：
> ```bash
> for d in $(git ls-files | grep -E 'db/migration/.*\.sql$' | sed 's#/db/migration/.*##' | sort -u); do
>   printf '%-58s max V%s\n' "$d" "$(ls $d/db/migration | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | tail -1)"
> done
> ```

**每个目录对应哪个库**（回滚时要按库核，别核错地方）：

| 迁移目录 | 数据库 | 默认 JDBC（prod profile） |
|---|---|---|
| `nexus-gateway` | `nexus_gateway` | `jdbc:mysql://mysql:3306/nexus_gateway` |
| `nexus-bridge` | `nexus_bridge` | `jdbc:mysql://mysql:3306/nexus_bridge` |
| `nexus-signing-service` | `nexus_signing` | 见 `deploy/config/init-mysql-dbs.sh` |
| `nexus-wallet-service` | `nexus_wallet` | `jdbc:mysql://mysql:3306/nexus_wallet` |
| `nexus-consortium/consortium` | 独立数据源 | — |

> **口径修正（2026-10-12 起）**：业务库是 **MySQL 8.0**，不是 PostgreSQL。PG 只承载 `nexus-core` 的链状态（`docker-compose.prod.yml` 的 `postgres` 服务 + `nexus-core` 的 `depends_on`）。`docs/migration-guide.md` 开头「数据库：PostgreSQL（生产）」是切换前的旧口径。
>
> 出处：`docker-compose.prod.yml:719-730`（MySQL 业务库注释与切换原因）、`:703`（PG 只建 `nexus_core` 一个库）、`nexus-gateway/src/main/resources/application-prod.yml:33-36`、`deploy/config/init-mysql-dbs.sh`（4 个业务库名）。

**为什么不能填洞**：Flyway **未配 `out-of-order`**（`nexus-gateway/src/main/resources/application.yml:138-144`、`application-prod.yml:78-83`，其余模块同口径），默认 `false`。在已应用 V110 的存量库上塞一支 V99，启动直接抛 `Detected resolved migration not applied to database`，**服务起不来**。空洞（V67–V70、V72–V80、V99–V102）永久留着。

- **出处**：`docs/migration-guide.md` 的「迁移版本号分配规则」；`scripts/check-migration-version-ratchet.py` docstring。

---

## 三、升级顺序（依赖拓扑决定）

顺序不能乱：下游服务在启动时会连上游，上游没起来它就会一直重启。

```
postgres / mysql / redis / nacos / seata   （基础设施，不动）
        ↓
nexus-core                                  （链节点，所有人依赖它）
        ↓
mpc-engine-0/1 → nexus-signing-service      （签名；signing 依赖 mpc-engine）
        ↓
nexus-wallet-service  ∥  nexus-bridge       （互不依赖，可并行）
        ↓
nexus-gateway                               （依赖 core + nacos + mysql）
        ↓
nexus-api-gateway                           （依赖 gateway + bridge + redis）
nexus-explorer                              （依赖 core）
```

**出处**：`docker-compose.prod.yml` 各服务的 `depends_on`（`nexus-gateway`:86-92、`nexus-core`:126-128、`nexus-signing-service`:181-187、`nexus-wallet-service`:230-236、`nexus-bridge`:271-277、`nexus-api-gateway`:305-311、`nexus-explorer`:337-339）；静态清单顺序见 `deploy/k8s/README.md:5-19`。

**共识变更的额外要求**：`nexus-core` 这一层必须**全部验证节点同时切**（同一维护窗口内），不能滚动升级。

---

## 四、升级执行

### 4.1 发布产物从哪来

镜像与 chart 由 **tag push** 触发（`.github/workflows/release.yml:11-13`），镜像 tag 就是 `github.ref_name`（**含 `v` 前缀**），chart 以纯 semver 推到 OCI（`:318-322`）。

⇒ **不要手工 build 镜像打 tag 上线**，否则 `check-version-consistency.sh` 的断言 8 与线上实际不一致，下一次归版必然对不上。

### 4.2 Helm 路径（推荐）

```bash
# 逐层升级，每层等 rollout 完成再下一层
helm upgrade nexus-core     oci://ghcr.io/levango7/nexuschain/charts/nexus-chain \
  -n nexus -f deploy/helm/values-prod.yaml --set <core 层参数> --version <X.Y.Z>
kubectl -n nexus rollout status statefulset/nexus-core --timeout=10m

helm upgrade nexus-signing-service ... ; kubectl -n nexus rollout status deploy/nexus-signing-service --timeout=5m
helm upgrade nexus-wallet-service  ... ; kubectl -n nexus rollout status deploy/nexus-wallet-service  --timeout=5m
helm upgrade nexus-bridge          ... ; kubectl -n nexus rollout status deploy/nexus-bridge          --timeout=5m
helm upgrade nexus-gateway         ... ; kubectl -n nexus rollout status deploy/nexus-gateway         --timeout=5m
helm upgrade nexus-api-gateway     ... ; kubectl -n nexus rollout status deploy/nexus-api-gateway     --timeout=5m
```

Deployment 注入了 `checksum/config` / `checksum/secret`，ConfigMap/Secret 变更会自动触发滚动重启；更新策略 `maxUnavailable: 0` + `maxSurge: 1`（`deploy/helm/README.md:142-146`）。

### 4.3 静态清单 / compose 路径

```bash
kubectl apply -f deploy/k8s/00-namespace-config.yml
kubectl apply -f deploy/k8s/30-infrastructure.yml
kubectl apply -f deploy/k8s/20-core-statefulset.yml
kubectl apply -f deploy/k8s/25-signing.yml
kubectl apply -f deploy/k8s/26-wallet.yml
kubectl apply -f deploy/k8s/15-bridge.yml
kubectl apply -f deploy/k8s/10-gateway.yml
kubectl apply -f deploy/k8s/27-api-gateway.yml
kubectl apply -f deploy/k8s/28-explorer.yml
kubectl apply -f deploy/k8s/40-monitoring.yml
kubectl apply -f deploy/k8s/50-backup.yml
kubectl apply -f deploy/k8s/60-networkpolicy.yml
```

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

> **`deploy/k8s/README.md:5-19` 的官方顺序是 `00 → 30 → 20 → 10 → 28 → 40 → 50 → 60`——但它只列了 8 个文件，漏掉了 `15-bridge` / `25-signing` / `26-wallet` / `27-api-gateway`。**上面这段是按依赖拓扑补齐后的完整顺序（第三节的拓扑为准，文件名前缀只是辅助信号）。
>
> **`60-networkpolicy.yml` 必须最后应用**——它是 `default-deny-ingress` + 逐条放行（`deploy/k8s/60-networkpolicy.yml:11-20`）。它**只限制 Ingress**，且第 1 条规则显式放行命名空间内互访，所以早应用**不会**切断服务间调用；但它会**立即拒绝外部入站**（`ingress-nginx → gateway:8080`、外部验证者 `→ core:9585` P2P）。放在最后，是为了让外部入口在内部就绪之后才切换。

---

## 五、升级后验证（五条，按依赖顺序）

```bash
# 1) 迁移真的应用了，且没有失败行。
#    ⚠ 业务库是 **MySQL**（见 2.1 的库表对照），不是 PostgreSQL——
#    PostgreSQL 只承载 nexus-core 的链状态，Flyway 迁移不在它上面。
kubectl -n nexus exec -it statefulset/mysql -- sh -c \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" nexus_gateway \
   -e "SELECT version, success FROM flyway_schema_history WHERE success = false;"'
# 期望：Empty set（0 行）

# 2) 健康（/actuator/health 公开给探针，不需要 token）
curl -fsS http://<gateway-pod>:8080/actuator/health        # {"status":"UP"}
curl -fsS http://<core-pod>:19585/actuator/health

# 3) 链在出块（dev/sandbox 单节点不产块是预期，见 alerts.md 噪音警告）
#    ⚠ /actuator/prometheus 需要 Bearer：未配 token → 503，token 错 → 401（不是服务坏了）
curl -s -H "Authorization: Bearer $NEXUS_METRICS_TOKEN" \
  http://<core-pod>:19585/actuator/prometheus | grep nexuschain_block_height

# 4) 最终性在推进（非 pos 模式无此序列，缺失不是故障）
curl -s http://<core-pod>:19585/consensus/finality

# 5) 版本口径与线上一致
bash scripts/check-version-consistency.sh
kubectl -n nexus get deploy -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'
```

**探针路径**（Helm 默认）：liveness / readiness 均为 `/actuator/health`（`deploy/helm/charts/nexus-gateway/values.yaml:68-80`）。**探针不通过不等于服务坏了**——启动期 `initialDelaySeconds: 40` 内本来就不 Ready。

**第 3 条的 503 / 401 不要误判**：`/actuator/prometheus` 是 fail-closed 的，token 未注入返回 **503**、token 不匹配返回 **401**，两种情况服务本身都是健康的。详见 [`metrics-auth.md`](metrics-auth.md)。

> **端到端验收缺口**：本仓**没有**一条命令验证「客户拿到交付物后，一笔支付能走完 下单→查询→结算确认→对账」。上面的五条只证明「进程活着、链在动」，**不证明资金链路通**。这条缺口已登记，验收脚本尚未合入 master。

---

## 六、什么时候该回滚（判据，不是感觉）

**先分类再动作**——三类问题的处置完全不同：

| 现象 | 属于 | 动作 |
|---|---|---|
| 迁移 `success=false` / 服务启动即退 / 健康检查持续 DOWN | **升级失败** | 回滚（第七节） |
| 服务起来了，但资金链路某一步 5xx / 结算不推进 | **功能回归** | 先看能不能前滚修；**不能盲滚**（见 6.1） |
| 链高停止增长 / 最终性水位冻结 / 出现分叉 | **共识事故** | **不要回滚**，按链故障处置 + 快照恢复 |

### 6.1 为什么「资金链路出问题」不能直接回滚

本仓迁移是**加性 DDL**（第八节有实测证据），回退镜像后旧代码不读新表/新列——**数据不会丢**。但**新版本已经写入的行对旧版本不可见**：

- 举例：`V109__approval_execution_link.sql` / `V110__approval_execution_link_nullable_approval_id.sql` 建的 `approval_execution_link` 表，记录的是「某笔审批执行过」。旧版本不认识这张表 ⇒ 回滚后同一笔审批可能被**再执行一次**。
- 同理 `V108__auto_withdraw_payout_claim.sql` 的「出款认领」列：回滚后认领状态不可见 ⇒ 自动提现可能重复出款。

⇒ **含资金状态机的版本，回滚前必须先停掉对应业务入口**（暂停自动提现 / 暂停审批执行），并确认在途记录已处理完，再回退镜像。

### 6.2 回滚窗口

迁移是加性的 ⇒ **没有「数据库已经改了所以回不去」这种硬截止**。真正的截止是**业务语义**：一旦新版本写入的、旧版本看不见的状态被外部系统（银行、链上、商户账单）引用，回滚就等于制造账实不符。**这个时点由业务 owner 判断，不由运维判断。**

---

## 七、回滚步骤

### 7.1 应用层（可回滚，且是主要手段）

```bash
# Helm：回到上一个 revision（会同时回退镜像 tag 与 chart 配置）
helm history nexus-gateway -n nexus
helm rollback nexus-gateway <REVISION> -n nexus
kubectl -n nexus rollout status deploy/nexus-gateway --timeout=5m

# 静态清单 / kubectl：直接 undo
kubectl -n nexus rollout undo deploy/nexus-gateway

# compose：改回 tag 再 up（不要 `down`——会删网络与卷挂载）
#   把 .env.prod / compose 里的镜像 tag 改回 v<上一版本>，然后：
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

**回滚顺序 = 升级顺序的逆序**：`api-gateway` → `gateway` → `bridge`/`wallet` → `signing`（+ mpc-engine）→ `core`。

> `helm rollback` 只回退 chart 与镜像，**不回退数据库**——见 7.2。

### 7.2 数据库（默认不需要回滚）

**结论：在本仓现有迁移形态下，回退应用版本时不要回滚迁移。** 理由见第八节的实测证据（全部加性 DDL）。把新表/新列留着，旧代码忽略它们即可。

只有当迁移**本身**就是故障源（例如某支 `CREATE UNIQUE INDEX` 因存量脏数据失败、或新表把磁盘写满）时才需要手工处理。**注意 4 个 MySQL 业务库各有独立的 `flyway_schema_history`**，要按库分别查：

```sql
-- 先确认是哪一支失败（在对应的库里执行，别在 nexus_gateway 里查 nexus_wallet 的迁移）
SELECT installed_rank, version, description, success
FROM flyway_schema_history WHERE success = false;

-- 手工撤销该迁移的 DDL 之后，删掉失败行（Flyway 下次启动会重新尝试该版本）
DELETE FROM flyway_schema_history WHERE version = '<失败版本>' AND success = false;
```

**逐支回滚的写法没有统一模板**——`docs/migration-guide.md` 只给了 V12、V18 两支的历史范例。其余 113 支需要逐支读 SQL 再写逆向语句。**这是当前的真实缺口，不要假设有 `flyway undo`**（Flyway 社区版不支持 undo，本仓也没有配）。

### 7.3 链数据（不可回滚，只能恢复）

链状态是共识产物，**没有「回退到上一个区块」这个操作**。可选路径只有两条：

1. **快照恢复**：从 `deploy/k8s/50-backup.yml`（备份 CronJob）或 `deploy/scripts/backup-mysql.sh` 的产物恢复整库；
2. **重放重建**：从 genesis 重新同步（只在链很小时可行）。

**恢复前必须做**：把当前库/链数据**另存一份**。覆盖式恢复会毁掉唯一能用来事后定责的现场。

- **出处**：`deploy/k8s/50-backup.yml`、`deploy/scripts/backup-mysql.sh`、`deploy/config/backup-databases.txt`。

---

## 八、数据库迁移的实测形态（回滚安全性的依据）

三条命令，在本仓 `origin/master` 上实测，结果都是**空**：

```bash
# ① 迁移里有 DROP 语句吗？（唯一命中是 nexus-consortium V1 的注释文字，不是 SQL）
git grep -i 'drop' -- '*/db/migration/*.sql'

# ② 有数据修正（UPDATE / DELETE）吗？
git grep -iE '^\s*(update|delete)\s' -- '*/db/migration/*.sql'

# ③ 有「新增 NOT NULL 且不给 DEFAULT」的列吗？（这种列会让旧代码插入失败）
git grep -iE 'add (column )?.*not null' -- '*/db/migration/*.sql' | grep -vi default
```

**结论**：5 个迁移目录共 115 支脚本（93 + 8 + 5 + 8 + 1），全部是 `CREATE TABLE` / `ALTER TABLE ADD` / `CREATE INDEX` 这类加性 DDL。⇒ **旧版本代码在新 schema 上可以直接运行**，这是「回退镜像不必回滚 DB」的依据。

**这个结论的边界**（别外推）：

- 它只保证**结构兼容**，不保证**语义兼容**（见 6.1：新表里的行旧版本看不见）；
- 一旦有人提交了第一支 `DROP` 或 `UPDATE` 迁移，本结论立即失效，**需要重新跑上面三条命令**；
- 唯一约束/外键是加性 DDL，但会让**旧版本的写入**失败（旧代码可能写重复值）——加约束的版本回滚时，要把「旧代码是否会违反该约束」单独看一遍。

---

## 九、已知缺口（诚实标注）

1. **历史区块交易类型扫描工具不存在**（第一节）——涉及交易类型门禁的升级，扫历史区块这一步目前只能手工重放。
2. **端到端资金链路验收脚本未合入 master**（第五节）——「服务活着」与「能收款」之间没有自动断言。
3. **没有回滚演练记录**——本文第七节的三条路径（`helm rollback` / `rollout undo` / 快照恢复）**【未演练】**，本机无集群。
4. **`docs/migration-guide.md` 只覆盖 `nexus-gateway` 一个目录**——另外 4 个数据源的迁移没有任何逐支说明。
5. **逐支逆向语句缺失**——除 V12 / V18 两支外，其余迁移没有回滚脚本。

---

## 十、检查清单（可直接抄进发布单）

```
[ ] 共识变更判定：是 / 否        （是 ⇒ 全节点同窗口切换 + 先扫历史区块）
[ ] check-version-consistency.sh           绿
[ ] check-migration-version-ratchet.py     绿
[ ] docker compose ... config -q           通过
[ ] check-packaging-consistency.py         绿
[ ] 迁移取号依据已写进 PR 正文（按目录 max+1）
[ ] 数据库已备份，且备份**可读**（不是只看到文件存在）
[ ] 回滚预案已写：回退到哪个 revision、谁批、什么时点之后不能再退
[ ] 升级后五条验证全过
[ ] 24 条告警在升级后 30 分钟内无新增 firing
```

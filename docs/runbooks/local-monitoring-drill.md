# Runbook：本地监控栈告警演练（PLAN-074 可复现版）

- **适用**：在**单台开发机**上复现「指标被抓取 → 规则求值 → 告警送达」的真实链路
- **不适用**：真实集群、物理多机、生产容量/延迟结论
- **证据来源**：`docs/plan/PLAN-074-local-alert-drill.md`（2026-11-04 实测）
- **本文件解决**：roadmap B 组「监控栈实验脚本与日志留在仓库外（验证资产未入库、
  不可复现）」——把**步骤、参数、预期观测**固化进仓库；原始日志与 webhook 落盘
  仍留在仓库外，不把一次性实验产物当代码入库。

> **口径**：本 runbook 是「怎么再跑一遍」的配方，不是新的实测结论。
> 引用数值时请同时引用 PLAN-074 的实测记录与本文的边界。

## 一、边界（先读，避免过度引用）

1. **同内核单机**：WSL2 / Windows 本机进程互连，**不是物理多机**，不构成分布式结论；
2. **不是真实机房**：Prometheus / Alertmanager 是自下载社区二进制，
   不是集群内 kube-prometheus-stack；无 ServiceMonitor、无多副本抓取拓扑；
3. **不宣称「告警已在真实集群触发过」**；
4. 本 runbook 用 **15s scrape + 30s 应用采样**；生产可能是 30s/15s，数值不可直接对比；
5. **不改任何告警阈值**；若为缩短等待改 `for:`，只改演练副本，并明确标注
   「这不是生产规则的证据」；
6. **不写任何密钥**：`NEXUS_METRICS_TOKEN` 用一次性随机值，只写临时文件，不入库；
7. 原始日志、webhook 落盘、一次性脚本仍留仓库外（见 §八）。

## 二、与 PLAN-074 的差异（2026-11-05 之后）

PLAN-074 取证时 `/actuator/prometheus` **尚未鉴权**。PLAN-075 起该端点要求
`Authorization: Bearer <NEXUS_METRICS_TOKEN>`，未配置 token 时返回 **503**，
token 错误返回 **401**。因此本 runbook 比 PLAN-074 多两步：

- 启动 nexus-core 时显式注入 `NEXUS_METRICS_TOKEN`；
- Prometheus 抓取配置加 `bearer_token_file`。

若跳过这两步，`up=0` 且 `lastError=401/503`，不是产品故障。

## 三、环境矩阵（PLAN-074 实测值）

| 组件 | 版本 | 位置示例 | 端口 |
|---|---|---|---|
| PostgreSQL | 16.15 | WSL2 Ubuntu-24.04 | 5432 |
| nexus-core | 2.77.0-SNAPSHOT（master） | WSL 内 `:nexus-core:nexus-core:run` | 19585 |
| Prometheus | 3.15.1 | `/opt/local-tools` | 9097 |
| Alertmanager | 0.34.2 | `/opt/local-tools` | 9098 web / 9099 cluster |
| webhook 接收器 | python3 | 任意可写目录 | 9094 |

**端口选择**：`9090-9091` 常被本机 Docker 占用；Alertmanager 默认 gossip 端口
与 webhook 冲突。先用 `ss -ltn` + `docker ps --format '{{.Names}} {{.Ports}}'`
核对，再固定用 **9097 / 9098 / 9099 / 9094**。

## 四、准备

```bash
DRILL=/tmp/nexus-monitoring-drill
mkdir -p "$DRILL"/{data,hooks}
cd /path/to/NexusChain
```

### 4.1 数据库

```bash
sudo service postgresql start
sudo -u postgres psql -v ON_ERROR_STOP=1 <<'SQL'
CREATE ROLE nexus LOGIN PASSWORD 'drill-only-not-a-secret';
CREATE DATABASE nexus_drill OWNER nexus;
SQL
```

> 密码只用于本机演练库；不要复用任何真实凭据。

### 4.2 工具与规则

下载 Prometheus 3.15.1 / Alertmanager 0.34.2（GitHub releases），按发布页公布的
sha256 校验。PLAN-074 记录的前缀分别为 `6a726885…`（Prometheus）、
`17ee14ac…`（Alertmanager）；**完整值以发布页为准，本文不复制**。

```bash
python3 scripts/extract-prometheus-rules.py \
  deploy/k8s/50-prometheus-rules.yaml "$DRILL/rules.yaml"
promtool check rules "$DRILL/rules.yaml"
```

预期：`SUCCESS: 24 rules found`（数量随 master 演进，以实际输出为准）。

## 五、启动顺序

> 以下命令默认在同一个 shell 里执行；新开终端先补 `DRILL=/tmp/nexus-monitoring-drill`。

### 5.1 nexus-core

```bash
export DATA_SOURCE_URL='jdbc:postgresql://127.0.0.1:5432/nexus_drill'
export DB_USERNAME=nexus
export DB_PASSWORD='drill-only-not-a-secret'
export CONSENSUS_MODE=pos
export FINALITY_STORE=jdbc
export NEXUS_JWT_APP_ID='drill'
export NEXUS_JWT_APP_SECRET='drill-only-not-a-secret-0123456789abcdef'
export NEXUS_METRICS_TOKEN="$(openssl rand -hex 32)"
printf '%s' "$NEXUS_METRICS_TOKEN" > "$DRILL/token"

./gradlew :nexus-core:nexus-core:run
```

另开一个终端验证：

```bash
curl -fsS http://127.0.0.1:19585/actuator/health
curl -fsS -H "Authorization: Bearer $(cat "$DRILL/token")" \
  http://127.0.0.1:19585/actuator/prometheus | head
```

预期：health 为 `UP`；metrics 为 200 且文本含 `nexus_finality_outbox_*`。
若为 503：`NEXUS_METRICS_TOKEN` 没注入；若为 401：token 不一致。

### 5.2 webhook 接收器（演练用，不是产品代码）

`$DRILL/webhook.py`：

```python
#!/usr/bin/env python3
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

OUT = os.environ.get("HOOK_DIR", "hooks")
os.makedirs(OUT, exist_ok=True)
seq = 0


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        global seq
        n = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(n)
        seq += 1
        with open(os.path.join(OUT, f"hook-{seq:03d}.json"), "wb") as f:
            f.write(body)
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass


HTTPServer(("127.0.0.1", 9094), Handler).serve_forever()
```

```bash
HOOK_DIR="$DRILL/hooks" python3 "$DRILL/webhook.py" &
```

### 5.3 Alertmanager

`$DRILL/alertmanager.yml`：

```yaml
route:
  receiver: drill-webhook
  group_by: ['alertname']
  group_wait: 1s
  group_interval: 5s
  repeat_interval: 1m

receivers:
  - name: drill-webhook
    webhook_configs:
      - url: 'http://127.0.0.1:9094/'
        send_resolved: true
```

```bash
alertmanager \
  --config.file="$DRILL/alertmanager.yml" \
  --web.listen-address=127.0.0.1:9098 \
  --cluster.listen-address=127.0.0.1:9099 &
```

### 5.4 Prometheus

`$DRILL/prometheus.yml`：

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - /tmp/nexus-monitoring-drill/rules.yaml

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['127.0.0.1:9098']

scrape_configs:
  - job_name: nexus-core
    metrics_path: /actuator/prometheus
    bearer_token_file: /tmp/nexus-monitoring-drill/token
    static_configs:
      - targets: ['127.0.0.1:19585']
```

```bash
promtool check config "$DRILL/prometheus.yml"
prometheus \
  --config.file="$DRILL/prometheus.yml" \
  --web.listen-address=127.0.0.1:9097 \
  --storage.tsdb.path="$DRILL/data" &
```

预期：`http://127.0.0.1:9097/targets` 中 `nexus-core` 为 `UP`。

## 六、四类事实的观测

### 6.1 真实 PG 下积压数精确（旧实现会低报 64）

```bash
sudo -u postgres psql -d nexus_drill -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO finality_vote_outbox
  (epoch, checkpoint_hash, validator, origin, created_at)
SELECT g, repeat('a', 64), 'drill-validator', 'local', 0
FROM generate_series(1, 100) AS g
ON CONFLICT DO NOTHING;
SQL
```

等一个采样周期（≤30s）后：

```bash
curl -fsS -H "Authorization: Bearer $(cat "$DRILL/token")" \
  http://127.0.0.1:19585/actuator/prometheus \
  | grep -E 'nexus_finality_outbox_(database_pending|database_readable)'
```

预期：

```
nexus_finality_outbox_database_pending 100.0
nexus_finality_outbox_database_readable 1.0
```

说明：这些假行会被 outbox 重放路径处理。PLAN-074 实测**一个采样周期内**
`database_pending` 仍为 100；若你的实现已把脏行拒绝并移除（PLAN-072 的
脏行处理路径），读数会下降——以抓取时刻的实际值为准。要稳定制造积压，
可在采样窗口内重复插入，使积压量高于 outbox 的补写速度。

### 6.2 停库后 `readable=0` + `pending=NaN`

```bash
sudo service postgresql stop
```

等一个采样周期后抓取同一端点，预期：

```
nexus_finality_outbox_database_pending NaN
nexus_finality_outbox_database_readable 0.0
```

同时观察语义切换：

- `FinalityOutboxBacklog` 应转为**非 firing**（`database_pending` 为 NaN ⇒ 表达式不成立）；
- `FinalityOutboxUnreadable` 应在 `for: 5m` 后 **firing**。

恢复：

```bash
sudo service postgresql start
```

### 6.3 抓取文本形状

```bash
curl -fsS -H "Authorization: Bearer $(cat "$DRILL/token")" \
  http://127.0.0.1:19585/actuator/prometheus \
  | grep -E '^# TYPE nexus_finality_outbox_|^nexus_finality_outbox_'
```

预期 9 条序列：

| 类型 | 序列 |
|---|---|
| gauge | `nexus_finality_outbox_enabled` |
| gauge | `nexus_finality_outbox_pending{origin="local"}` |
| gauge | `nexus_finality_outbox_pending{origin="received"}` |
| gauge | `nexus_finality_outbox_database_pending` |
| gauge | `nexus_finality_outbox_database_readable` |
| counter | `nexus_finality_outbox_enqueued_total` |
| counter | `nexus_finality_outbox_flushed_total` |
| counter | `nexus_finality_outbox_failed_total` |
| counter | `nexus_finality_outbox_dropped_total` |

counter 必须带 `_total` 后缀；promtool 吃的是合成序列，**不是**这个真实抓取文本。

### 6.4 告警真实触发并送达

```bash
curl -fsS http://127.0.0.1:9097/api/v1/alerts | python3 -m json.tool
curl -fsS http://127.0.0.1:9098/api/v2/alerts | python3 -m json.tool
ls -1 "$DRILL/hooks"
```

预期（PLAN-074 实测形态）：

| 告警 | Prometheus | Alertmanager | webhook |
|---|---|---|---|
| `FinalityOutboxBacklog`（100 行，`for: 10m`） | firing，`value=1e+02` | active | `status=firing` |
| `FinalityOutboxUnreadable`（停库，`for: 5m`） | firing | active | `status=firing` |

> **等待时间**：生产规则 `for: 10m` / `for: 5m`。要快速冒烟，可在**规则副本**
> 里把 `for` 调到 30s，但必须标注「只验链路，不是生产规则证据」。

## 七、失败排查（PLAN-074 实测踩坑）

| 现象 | 原因 | 处置 |
|---|---|---|
| Prometheus `bind: address already in use` | 9090/9091 被本机 Docker 占用 | 用 9097，并先 `ss -ltn` / `docker ps` |
| Alertmanager `gossip mesh … port 9094` | 默认 gossip 端口与 webhook 冲突 | 显式 `--cluster.listen-address=127.0.0.1:9099` |
| `pgrep -f alertmanager` 误判 | 匹配到 java classpath 含该词的 nexus-core | 用 `pgrep -af <完整二进制路径>` |
| `set -u` 下 `FINALITY_STORE: unbound variable` | 变量未设 | `${VAR:-default}` 并显式 `export` |
| WSL `nohup … &` 会话结束即退出 | `wsl -e bash -c` 结束会清理子进程 | 用持久侧脚本 + Windows `Start-Process` 托管，日志写 `/mnt/c` |
| `up=0` + `lastError=401` | token 不一致 | 重写 `$DRILL/token` 并重启 Prometheus |
| `up=0` + `lastError=503` | 应用未注入 `NEXUS_METRICS_TOKEN` | 重启 nexus-core 并注入 |
| 按端口清杀误杀 nexus-core | 端口集合误含 19585 | 清杀集合**绝不**包含 19585/5432 |
| `database_pending` 很快归零 | 假行被 outbox 重放路径拒绝并移除 | 在采样窗口内重复插入，或接受"曾经积压"这一观测 |

## 八、清理

```bash
# 只杀监控进程；不要按端口清杀 19585/5432
pkill -f '/opt/local-tools/prometheus'
pkill -f '/opt/local-tools/alertmanager'
pkill -f "$DRILL/webhook.py"
sudo -u postgres psql -c 'DROP DATABASE IF EXISTS nexus_drill;'
rm -rf "$DRILL"
```

原始日志、`hooks/` 落盘与一次性脚本按既有纪律留在仓库外；本文件只保留可复现步骤。

## 九、与 CI 门禁的关系

本 runbook **不是** CI 门禁：CI 只跑 `FinalityRulesCounterUsageTest` 等静态/契约
测试与 promtool 规则检查。真实链路仍需按本文手工执行；若要进 CI，需要先解决
「起 PG + 三进程 + 等 10 分钟告警」的成本与稳定性问题，属独立切片。

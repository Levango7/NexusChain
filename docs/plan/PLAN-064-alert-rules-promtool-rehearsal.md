# 方案 PLAN-064：告警规则的 promtool 演练（阈值 + for 状态机）

- **状态**：已交付（2026-11-01）
- **前置**：PLAN-062/063（指标名契约——规则引用的名字必须与代码注册一致）、
  PLAN-050（`FinalityStalled` 测试床演练，§五 明确 `for` 状态机不在其范围）、
  PLAN-062 §五 / PLAN-063 §七 抓取端补验（有意边界："只证明名字与取值形态
  在线上，不证明告警按阈值触发"）
- **范围**：门禁变更 + **两处告警表达式修复**（首跑抓到的死告警）；**零 Java/
  产品代码改动**
- **不包含**：Prometheus/Alertmanager 起服与路由、通知链路、真实集群抓取新证据

---

## 一、问题（为什么必须做）

1. **静态校验面**：`k8s-sync-check.yml` 的「PrometheusRule 静态校验」只做
   YAML parse + 告警计数（`>= 5`）+ `for:` 存在性检查——**18 条表达式零解析、
   零触发验证**。规则文件自己的注释也承认："promtool 校验 PromQL 表达式需
   二进制安装，不强制"，理由写的是"应用 prom metrics 大部分 TODO（2.x 阶段
   补）"——该理由早已过时（应用指标 10/12 已注册并有名字契约门禁）。
2. **已登记但未闭合的边界**：
   - PLAN-062/063 抓取端补验只到"指标存在 + 取值形态"；
   - PLAN-050 §五："`for: 10m` 的 pending→firing 状态机由规则语义保证，
     **不在本实验范围**"。
3. **同型教训反复出现**：PLAN-058（@Component 缺失）、PLAN-059（门禁覆盖面
   只覆盖一个模块）、PLAN-062/063（规则引用名字漂移）——都是"看起来有门禁，
   实际守护的范围小于名义"。"写对名字但永不触发"正落在现有全部门禁之外。

## 二、方案

| 件 | 内容 |
|---|---|
| `scripts/extract-prometheus-rules.py` | 从 CRD 提取 `spec.groups` → 普通 rules 文件（逐键透传，不改写表达式）；供 check/test 与本地复跑 |
| `deploy/k8s/tests/prometheus-rules.test.yaml` | promtool 单测：**38 用例 / 56 条断言**，覆盖全部 18 条告警的「阈值触发 / 不触发 / `for` 窗口未满不触发」三态；注解按 `{{ $labels.* }}` / `{{ $value }}` 实际展开后固化（promtool 对 `exp_annotations` 精确匹配） |
| `k8s-sync-check.yml` 新步「promtool 规则演练」 | `check rules`（18 条全解析）+ `test rules`；promtool 固定 **v3.15.1 + sha256**（下载 3 次重试）；本 workflow 的 paths 过滤覆盖 `deploy/**` |

**为什么挂在 k8s-sync-check 而不是 ci.yml**：规则文件在 `deploy/k8s/`，本
workflow 恰好由该路径触发；它虽非 required check，但失败会让 PR 的
`mergeStateStatus=UNSTABLE`——与"只合 CLEAN"的合并口径一致。

**本地复跑**（Windows 用 WSL 跑 promtool 即可）：

```bash
python3 scripts/extract-prometheus-rules.py \
  deploy/k8s/50-prometheus-rules.yaml /tmp/prom-gate/rules.yaml
cp deploy/k8s/tests/prometheus-rules.test.yaml /tmp/prom-gate/
cd /tmp/prom-gate && promtool check rules rules.yaml \
  && promtool test rules prometheus-rules.test.yaml
```

## 三、首跑发现

### 3.1 两条"从未触发"的死告警（`PodCpuUsageHigh` / `PodMemoryUsageHigh`）

用**旧表达式**跑单测（合成序列完全真实形态）：

- `container_memory_working_set_bytes` 恒为 limit 的 **95%**、持续 14 分钟
  （`for: 10m`）⇒ `got:[]`，**从不触发**；
- `container_cpu_usage_seconds_total` 恒定 85% 使用率 20 分钟 ⇒ 同理。

**根因**：`/` 是向量除法，要求两侧标签集一致（除度量名外）。LHS 是
`sum(...) by (pod, container)`（CPU）或裸 series（内存，带 `namespace/...`），
RHS 是裸 `kube_pod_container_resource_limits{...}`（多 `resource`，真实环境
还多 `job/instance`）⇒ 匹配恒空、告警恒不触发。

**修复**（告警配置，非产品代码）：两侧都 `sum by (pod, container)` 归一标签集
（`clamp_min(..., 1)` 保留）；缺 limit 序列时两侧无匹配、仍不触发——**原语义
保留**。修复后同一序列：恒定 95% ⇒ 11m 触发（10m `for` + 1m）；恒定 50% ⇒
15m 不触发。

### 3.2 告警计数漂移 17 → 18

文件头自称"告警数 17 条（11 + 4 + 2）"，实测 **18** 条：PLAN-052 新增
`BlockAdoptionAbstainRisk` 时未回头修正。CI 只断言 `>= 5`，该漂移从不触发。
本次同步修正为 18 并附原因。

### 3.3 方法记录（给未来的自己）

- `max()` 聚合会**丢掉** `job/pod` 标签：`BlockchainCoreStalled` 实际发出的
  告警只带 `severity/component`——期望标签必须以 promtool 实际输出校准；
- `rate()` 在样本窗早期的外推会系统性低估比率（起步阶段 `changes` 类断言不要
  贴着 `for` 边界写）：用例需留热身样本，触发断言留 ≥1min 裕量。

## 四、验证

- **本地**：promtool v3.15.1（tar 包 sha256 `6a726885…a84` 与官方
  `sha256sums.txt` 复核一致）；`check rules` **SUCCESS: 18 rules found**；
  `test rules` **SUCCESS**（38 用例 / 56 条断言）；
- **反向对照 A**（还原两处修复）：恰好两条用例红——`pod-cpu-usage-high@15m`、
  `pod-memory-usage-high@11m`（均 `got:[]`）；其余 36 用例不受影响；
- **反向对照 B**（`nexus_block_outbox_readable == 0` → `== 2`）：恰好
  `block-outbox-unreadable@6m` 一条红；
- **CI 布局模拟**：按 workflow 的步骤序列（提取 → 拷贝 → 相对路径
  `rules.yaml`）实跑全绿；合入以 k8s-sync-check 的实跑为准。

## 五、边界（勿夸大）

1. **不起真实 Prometheus/Alertmanager**：`for` 状态机由 promtool 单测覆盖，
   但 pending→firing→通知/路由/抑制链路的端到端仍在部署侧未验证；
2. **合成序列**：`kube_*` 用例不证明真实集群抓取目标存在（`nexus_*` 侧已在
   PLAN-062/063 补验）；本 PLAN 冻结的是**表达式语义**，不是真实数据下的
   误报率；
3. **非 required check**：靠 `UNSTABLE` 拦截，未改分支保护清单；
4. **promtool 下载依赖 GitHub releases**（固定版本 + sha256 + 3 次重试）；
   升级版本需同步改 workflow 两处；
5. 两处死告警的修复是"从永不触发到按设计触发"——**不等于**在真实集群上
   验证过阈值体验（那需要部署侧演练）。

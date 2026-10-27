# 方案 PLAN-054：MPC E2E job 的 apt 失败被 job timeout 拖长 45 分钟的韧性改进

- **状态**：已交付（2026-10-28）
- **前置**：PLAN-024（runner 标签切换，归因修正）、roadmap #32（排队/平台侧）、
  PR #298 的 CI run 取证
- **范围**：`ci.yml` 的两处 GMP apt 步骤 + `MPC Java Cluster E2E` job 的
  timeout/重试参数（**零测试代码改动**）
- **不包含**：MPC 测试内容、runner 镜像供应链（换 registry/mirror 另立）

---

## 一、现象（实测取证）

PR #298（纯文档）的 CI run `40538159453` 中：

- `MPC Java Cluster E2E (CGGMP21 3-node)` job 的 **`Install GMP toolchain + protoc`
  在 job 开始后约 18 秒（16:41:36）就结束**，日志明确
  `::error::apt 安装 GMP 工具链 连续 3 次失败; exit 1`；
- 但 job 直到 **17:26:37 才结束**（约 45 分钟），与 `timeout-minutes: 45` 一致；
- 整段日志无任何 gradle/测试输出——**apt 失败后 job 空转了 45 分钟**。

⇒ 观感上"MPC E2E 起不来/排队久"其实混合了三种：排队（≤2min，PLAN-053）+
apt 失败（~50s）+ **失败后的 45 分钟空转**（真正的大头）。

## 二、正常样本基线（API 只读）

最近 **22 个成功**的 `MPC Java Cluster E2E` job（started→completed）：

| 最短 | 中位 | 最长 |
|---|---|---|
| 4min | **8min** | **10min** |

## 三、改动（三处，都在 `ci.yml`）

1. **job timeout 45 → 15**（仍留 1.5× 余量于最长样本）；
2. **步级 `timeout-minutes: 6`**：挡住 apt 卡死（连接挂住不返回）；
3. **重试 3→5、间隔 15s→30s**：单步上限约 3 分钟覆盖更长平台抖动；
   重试耗尽仍 `exit 1` fail-fast，与 job timeout 15min 配合把
   **失败总时长从 ~45min 压到 ~10min**。

两处 GMP 步骤同步改（`mpc-java-cluster-e2e` + rust job 的同名步骤）。

## 四、影响与取舍

**收益**：apt 抖动场景下 PR 门禁的假性 pending 从 ~45min 降到 ~10min，
watcher/合并等待不再被无效空转吃掉；runner 占用同比例下降。

**代价（如实）**：若某次 job 真的需要 >15min（如 MPC 集群 E2E 偶发长尾 +
runner 繁忙），可能被 timeout 误杀。当前证据不支持：22 个成功样本全部 ≤10min。
若未来出现 10-15min 区间样本，按 §五 的 revisit 上调。

## 五、验证与 revisit

- 改动后首次 CI 复跑观察：apt 步骤耗时 & job 总时长（正常应 ≤10min）；
- **revisit 条件**：出现成功样本 >10min ⇒ 把 job timeout 上调到 20-25；
  出现 apt 失败空转仍 >10min ⇒ 检查步级 timeout 是否生效；
  apt 失败率上升 ⇒ 评估带緩存/镜像源的 runner（改成本仓镜像供应链）。

## 六、边界（勿夸大）

- 单次失败样本 + 22 个成功样本分布；不宣称"45 分钟空转每次都发生"；
- 不改 runner 标签（PLAN-053：24.04 供给最足）；
- 不引入自定义镜像（无此资产，引入成本高于收益）。

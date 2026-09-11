# mpc-engine 零密钥兜底策略评估：fail-warn vs fail-closed

> 2026-09-11 C9 决策材料——SealedSecret 被删除/未注入时 mpc-engine 的
> 启动行为选择。**本文档只评估利弊供拍板，不改行为。**

## 现状（实测，2026-09-10 kind 演练）

删除 SealedSecret → Secret 级联删除 → Pod 重启后：

- init-container 检测 `STORAGE_KEY` 空 → **WARN 日志 + 全零密钥（000…0）兜底**
- mpc-engine **正常启动**（fail-warn）：gRPC 服务在、健康探针过
- 后果：**既有会话数据全部无法解密**（AES-256-GCM 用错密钥 = 数据作废），
  但新会话用全零密钥建立——**两套密钥的会话混存**，恢复真密钥后新会话
  又解不开

代码位置：`deploy/helm/charts/mpc-engine/templates/statefulset.yaml`
init-config（`STORAGE_KEY_VAL="000…0"` 兜底分支）+ `mpc-engine/src/config.rs`
（storage_key_source="plain" 读全零值无校验）。

## 两个方向

### 方案 A：维持 fail-warn（现状）

**优点**
- 可用性优先：SealedSecret 控制器抖动/ Helm 升级窗口内服务不中断
- 引擎仍能响应探针，运维面板显示"健康"便于排查

**缺点（核心风险）**
- **静默数据损坏**：全零密钥下产生的新会话，在恢复真密钥后**永久不可解密**
  ——这是资金相关状态（keyshare 分片、签名会话），损坏不可逆
- 健康探针全绿 = 监控盲区：SealedSecret 删除这种严重事件在 dashboards
  上**无任何异常信号**（11 条告警无一覆盖此场景）
- 与 MPC 安全定位矛盾：签名服务应"宁可拒绝服务，不可用错密钥"

### 方案 B：fail-closed（密钥缺失/全零时拒绝启动）

**优点**
- 消除静默损坏：无真密钥 → 容器 CrashLoop → **告警立刻可见**
  （MpcEngineCrashLooping 告警已存在且实测有效）
- 数据一致性：恢复真密钥后重启，全部会话可解密（要么全真密钥会话，
  要么不工作，不混存）
- 符合资金系统"fail-closed 优于 fail-degraded"的行业惯例
  （与本项目 SecurityConfig JWT 密钥缺失即 fail-closed 的既有决策一致）

**缺点**
- 可用性下降：SealedSecret 控制器短暂不可用时引擎不可用
- 需要区分"密钥真缺失"与"首次部署尚未注入"（init 脚本需要更明确的状态）
- 已有演练环境需同步改（当前 kind 演练依赖全零兜底起容器）

## 评估结论（建议）

**建议 B（fail-closed）**，理由：
1. 会话不可解密 = 实质数据丢失，其代价远大于短时不可用
2. 现有告警体系（MpcEngineCrashLooping + Deployment 不可用告警）天然
   覆盖 CrashLoop 场景，运维路径现成
3. 项目已有 fail-closed 先例（JWT 密钥），一致性更好

**若采纳，实施范围**（单批原子改动）：
- init-config：STORAGE_KEY 空时 `exit 1`（替代全零兜底）+ 明确 ERROR 日志
- config.rs：校验 storage_key 非全零（防"注入了占位值"的变体）
- 顺带收益：把"MPC_STORAGE_KEY 为全零/占位"纳入 50-prometheus-rules
  告警（引擎侧新增一个能暴露密钥健康状态的 metric，如
  `mpc_engine_storage_key_valid 0/1`）
- kind 演练冒烟（CI 的 mpc-kind-smoke）需同步：显式传 storageKey
  的场景不受影响（fail-closed 只拦"密钥缺失"，不拦显式冒烟值）

**若不采纳（维持 A）**：至少补降级告警——init-config 检测到全零兜底时
向 stderr 输出可被日志告警（Loki/Promtail）抓取的标记，或引擎暴露
`storage_key_degraded` metric 并加 PrometheusRule——消除监控盲区。

# 全交互、可访问性与本地可靠性收口（2026-11-22）

## 范围

承接「那就继续做啊」：补全键盘与可访问性、错误/空态/加载/重试矩阵、真实 PostgreSQL 停机恢复和 secured core 重启恢复。全部使用临时测试密钥和合成业务数据。

## 1. axe WCAG A/AA 与真实修复

新增 `@axe-core/playwright` 扫描，使用 `wcag2a/wcag2aa/wcag21a/wcag21aa`，不预置规则豁免。覆盖全部公开页面、资金/编排/提现保护页，以及亮色设置页。

实测并修复三族 `label-content-name-mismatch`：

- 首页 logo 与区块/交易卡片；
- 区块详情的上一/下一导航按钮；
- 合约卡片链接。

这些自定义 `aria-label` 覆盖了可见文本但不包含完整可见内容。现删除冗余 label，让屏幕阅读器直接读取完整内容；按钮角色、Tab 和 Enter 行为不变。

## 2. 键盘与焦点

- 编排 tab 实现 roving tabindex：左右键循环，Home/End 到首尾；`tab` 与 `tabpanel` 通过 `aria-controls/aria-labelledby` 关联。
- 资金三张长表的滚动容器增加 `role="region"`、唯一可访问名称和 `tabIndex=0`，实际用 PageDown 滚动后仍保持 sticky 表头。
- 清除凭证弹窗验证 Escape 关闭及焦点归还触发按钮。

## 3. 四态与错误语义

浏览器新增并验证：

- 合约：加载 → 数据、空态、500 错误 → 重试恢复；
- 编排：401 认证引导与 500 服务错误分流，错误后无旧数据残留；
- 提现：401 与 403 明确分流，刷新后可重取；
- 404：标题与回首页出口。

完整浏览器 **17/17**（含全部 axe 与上述交互）。

## 4. PostgreSQL 停机与恢复

`StateReplayManagerPostgresTest` 新增真实断连路径：首表写后、第二表写前关闭当前事务连接（不破坏 PG schema），恢复请求改由新事务管理器执行。

- 重放抛错（断库导致的 JDBC rollback 失败也按 `TransactionSystemException` fail-closed 接受，不强迫单一异常类型）；
- 恢复后先以 `SELECT 1` 等待 PG 可用，首表仍为 **0 行**，证明没有半提交；
- 数据库恢复后同一块重放成功，两表各 1 行；
- 连同提交/回滚/幂等用例共 **3/3**。

这不是 HA/主备切换声明，但证明单机真实 PG 故障下的事务原子性与可恢复性。

## 5. secured core 重启恢复

在既有 secured 钻机上扩展：先完成裸调 401 / BFF 200，再 `docker restart` core，等待重新进入鉴权就绪态，并连续 5 次读取 BFF 状态，全部 200 才通过。隔离容器和数据卷仍自动清理。

## 验证

- 完整浏览器：**17/17**；
- frontend：**221/221** + 完整 check；BFF：**8/8** + typecheck/lint/format；
- StateReplay 真实 PG：**3/3**；
- secured 初次握手及 core 重启后连续读取：全部 PASS；
- core 全量：`BUILD SUCCESSFUL`，包含上述真实 PG 断连恢复用例。

## 边界

- axe 是当前页面与规则集的自动检查，不等于屏幕阅读器人工验收或所有交互路径完成；
- 单机 PG 停机恢复不等于主备切换、物理多机或断电恢复；
- core 重启后连续 5 次读取是恢复判据，不是长期 SLA；
- 第三方 gateway/wallet 数据仍为 HTTP 合成，不是生产联调。

# 本地可验证收口批（2026-11-21）

## 范围

承接用户「可以，做吧」：在不要求云环境和商务凭证的前提下，完成 secured compose JWT 真握手、真实 PostgreSQL StateReplay 故障注入、其余页面真实浏览器验收。所有密钥和第三方数据均为本地临时/合成值。

## 1. secured compose 跨进程 JWT 握手

- 资产：`scripts/drill/secured_explorer_rpc.py` + `docs/drill/secured-explorer-rpc.md`。
- 真实容器：隔离 PostgreSQL、nexus-core、Explorer BFF；`LOCALONLY=true`，临时 APP_ID/SECRET 和对应 JWT。
- **裸调 core RPC = 401**；**BFF 带 token 调 core = 200，latestHeight=0**；容器与数据卷已清理。
- 钻机支持动态端口、服务 DNS alias、失败脱敏日志和自动清理，不把 `compose run --name` 误当服务 DNS 别名。

## 2. StateReplay 真实 PostgreSQL

- 新增 `StateReplayManagerPostgresTest`，复用 Testcontainers PG16，不写任何数据库口令。
- 验证：两表成功提交、第二表故障时首表回滚、重复重放幂等，**2/2 通过**。
- 首轮失败原因是测试床给 JdbcTemplate 与事务管理器使用了不同 DataSource；已修正为共享同一实例后复跑，未误报为产品缺陷。
- 与既有 H2 测试互补：H2 证明逻辑，真实 PG 证明方言与事务连接边界。

## 3. 全页面浏览器验收

`frontend/e2e/home.spec.ts` 现有 **10/10** 用例通过，覆盖仓内全部 12 个页面：

- 首页与资金看板：真渲染、桌面/移动几何、键盘搜索、三张长表 sticky；
- 区块、交易、地址、合约列表/详情、跨链：经真实 BFF 的可控 core 数据可见；
- 设置：凭证保存后掩码、主题切换；
- 支付编排：未配置先引导，配置后支付/连接器/规则三 tab 数据；
- 提现审批：连接配置 → 余额/队列/白名单；
- 404：明确空态与回首页出口。

移动视口统一断言无横向溢出，且无 `undefined` 契约泄漏。gateway/wallet 数据由 Playwright 在 HTTP 层合成，不宣称第三方生产联调。

### 新发现并已修复的真实缺陷

提现审批默认 `DEFAULT_WALLET_BASE=http://localhost:8083`，但 CSP 只放行 BFF 3000 与 gateway 8080，导致浏览器在发请求前即 `Failed to fetch`。现仅把 `localhost/127.0.0.1:8083` 两个明确的本地钱包源加入 `connect-src`，不放开任意 HTTP；同一浏览器用例转为通过。

## 边界

- secured 握手证明本地跨进程鉴权，不证明 staging/prod 部署、密钥轮换或真实云网络。
- PG 测试证明单机真实 PG 方言与事务，不证明主备切换、物理多机或断电恢复。
- 浏览器覆盖全部现有页面的基本链路，不等于完整视觉回归、全键盘/屏幕阅读器、四态矩阵或所有交互分支完成。
- 不接触真实 L1、生产支付、真实资金或长期容量/SLA。

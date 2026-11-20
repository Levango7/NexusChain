# 第二份集成缺陷报告复核与处置（2026-11-20）

- **复核对象**：用户第二份《NexusChain 集成/前后端联调缺陷报告》。
- **代码基线**：`origin/master` `664a105d`；前一批联调 PR #748 已合入 `7e39cf5b`。
- **方法**：逐条读当前实现，再用真实 Spring 上下文、真实 H2 事务、真实 HTTP、TypeScript 全门禁和真实浏览器复核；不按报告的 P0 标签直接改代码。
- **总结**：报告有多条高价值真缺陷，也混有「刻意 sandbox 行为」「当前代码已正确处理」和「不能靠降低安全阈值解决」的断言。本轮保留可取部分，修掉已证实缺陷，未把静态报告当成生产验收。

## 一、逐条结论

| 报告断言 | 复核结论 | 本轮处置与证据 |
|---|---|---|
| `JsonRpcController` 把所有 `ObjectNode` 都放进 `error` | **成立** | 成功区块、交易、合约详情同样是 `ObjectNode`，旧实现确实方向颠倒。现只在数值 `code` + 文本 `message` 的错误信封契约成立时写入 `error`，其余写 `result`；成功块/不存在块/未知方法回归 **3/3** |
| BFF 调 core `localonly` RPC 不带 token | **成立** | `LocalRpcInterceptor` 与 `RpcInterceptor` 都读 `token` 头，BFF 原实现没有。新增 `NEXUS_RPC_TOKEN`，仅部署环境注入；真实 HTTP 断言配置时转发、未配置不伪造，BFF 集成 **8/8** |
| 只加环境变量后，dev/prod compose 就会自然可用 | **不成立，需补编排** | dev core 默认 `LOCALONLY=true`，裸跑仍会 401。dev 在 core 仅绑 loopback 的编排中显式 `LOCALONLY=false`；prod 保持 true，并把 `NEXUS_JWT_APP_ID`、`NEXUS_JWT_APP_SECRET`、`NEXUS_RPC_TOKEN` 列为强制 secret。`.env.prod.example` 与 compose 强制项一致性脚本实测 **22/22** |
| CORS 预检被业务鉴权拦截 | **成立，完整 dev 上下文实测复现** | 修前 OPTIONS 已带 CORS 头却最终 **401 `Missing API key`**。API Key、HMAC、全局限流、租户鉴权/限流现对 OPTIONS 放行；修后预检 200 + 正确允许头，真实 POST 仍 **401 / code=40100**。完整 dev 测试 **3/3** |
| `SIMULATED-*` 是生产路径造假 | **部分成立，不能照单删除 sandbox** | 缺公钥或 `skipConfirmation=true` 时进入 sandbox 是明确设计，且结果带 `isSimulated=true`。真缺陷是旧 `queryStatus` 仅凭前缀把任意伪造字符串判 SUCCESS；现仅本进程实际签发的模拟哈希成功，未知/重启后不可辨认的哈希保守 PENDING |
| `StateReplayManager` 账户与孵化状态会半提交 | **成立** | 两次 JDBC 写原来无共同事务，且 `IncubatorDB` 吞异常返回 null。现 `replayBlock` 在同一事务中执行，失败向上抛出；真实 H2 故障注入证明第二表失败时首表回滚，成功时两表提交且重复重放幂等 |
| 报告之外的关联问题： replay 批量 SQL 的 `ON CONFLICT(id)` 可移植性 | **本轮实测发现** | H2 2.2.224 不支持带 conflict target 的写法；若保留会让回滚测试出现「首条也没写进去」的假阳性。两张表改为 PostgreSQL/H2 均可执行的 `ON CONFLICT DO NOTHING`，再跑真实提交/回滚，拒绝假绿 |
| StateDB 单验证人无法确认，应降低门槛 | **当前实现不支持该断言，且不得这样修** | PoS 分支本来就是 `max(1, ceil(active×2/3))`。新增直接方法级测试：1 个活跃验证人门槛 **1**，3 个活跃验证人门槛 **2**。多数派不足时停顿是安全边界，不能为「看起来有进展」削弱 |
| dev/sandbox 无 Zipkin 时缺 `Tracer` | **dev 可用，sandbox 缺口成立** | `NoopTracerConfig` 原仅 `@Profile("dev")`，而仓库文档支持 sandbox 启动。现扩展到 `dev,sandbox`；无 `@MockitoBean` 的完整 sandbox 上下文真实启动并注入 Tracer，测试通过 |
| 跨链页刷新无 loading/防重复 | **成立** | 刷新按钮接入统一 `Button.loading`，加载时禁用并带 `aria-busy`；延迟响应回归覆盖恢复过程 |
| Settings 成功提示定时器未清理 | **成立** | 重复保存先取消旧定时器，卸载时清理；fake timer 验证重复保存仅 1 个定时器、卸载后 0 个 |
| 编排页 401 未映射到认证引导 | **当前代码已处理** | `OrchestrationDashboard` 已区分 `ApiError.status===401`，显示认证提示和设置入口；不重复建设 |
| 表头不吸顶 | **页面头已有，资金表头缺口成立** | 全站 `PageHeader` 已 sticky，但资金看板三张 `<table>` 没有。三张表统一纵向滚动容器、sticky th、surface 背景与分隔线；除 DOM 契约外，真实浏览器逐表滚动并量取表头与容器顶部距离，通过 |
| 陈旧链头仍显示绿色「实时」 | **成立** | `useHeadStream` 明确 SSE 健康≠链在出块。首页改为仅最近 60 秒内有链头才显示实时，否则「非实时」；新鲜/陈旧两向回归 |
| 连接配置卡片未居中 | **成立** | 提现审批连接卡片 `max-w-2xl` 增加 `mx-auto`，DOM 回归通过 |

## 二、实跑验证

1. **Java 首轮全量**：core 全测试 + gateway `test + integrationTest + spotbugsMain`，`BUILD SUCCESSFUL`（20m58s）。
2. **Java 最终 gateway 全套**：补 sandbox Tracer 与 POST 反向后，`test + integrationTest + spotbugsMain` 再次 `BUILD SUCCESSFUL`（9m27s）。
3. **专项协议/数据证据**：
   - JSON-RPC 3/3；
   - StateReplay 既有回归 + 真实 H2 提交/回滚/幂等全部通过；
   - StateDB 门槛 1→1、3→2 通过；
   - dev CORS/POST/Tracer 3/3；sandbox 无 mock Tracer 1/1。
4. **BFF**：真实 HTTP 集成 **8/8**；typecheck、lint、format 全绿。
5. **frontend 完整 `check`**：22 个测试文件、**220/220**；TypeScript、Vite build、构建产物与设计对比度全绿。
6. **真实浏览器**：**5/5**。首页真渲染、桌面/移动几何、搜索/键盘，以及资金看板三张长表实际滚动后的 sticky 几何；页面由真实 BFF 托管 dist，资金网关数据由 Playwright 在 HTTP 层提供，不把业务网关联调冒称为已验收。
7. **编排**：dev compose 插值通过；prod compose 结构校验通过；`check-prod-env` 覆盖全部 **22** 个强制变量；patch whitespace 检查通过。

## 三、仍然不能虚标的边界

- 本 PR 不是生产就绪声明：real-L1、staging/prod、真实云集群、full/capacity、长期 SLA、法务许可均未闭合。
- BFF 已验证真实 HTTP token 转发，core 鉴权也有独立测试；但未在本轮启动完整 compose 做跨进程 JWT 握手，不能把这条写成 staging 联调完成。
- StateReplay 的原子性证据来自真实 H2 事务代理；真实 PostgreSQL 的故障注入/多副本一致性仍按原环境边界执行。
- 浏览器验收覆盖首页与资金看板，不代表其余页面、视觉回归、全键盘、可访问性、错误/空/无权限四态矩阵全部完成。
- 多数派确认阈值没有放宽；真实部署若只有不足 2/3 的活跃验证人在线，链停住需要恢复验证人/治理处置，而不是改成少数派可确认。

# 项目报告复核与完善版结论（2026-11-19）

- **复核对象**：用户提交的《NexusChain 公开仓库静态取证报告》全文。
- **当前基线**：`origin/master` `16859b91`；本轮分支 `fix/report-audit-20261119`。
- **方法**：不按报告原句照抄。逐条读当前源码/配置/工作流，能本机实跑的一律实跑；
  未实跑的环境（staging/prod/真 L1/持牌生产）一律不写成「已验证」。
- **实跑证据**：gateway 缺省 DLQ 钉子测试 2/2 绿；BFF 真 HTTP 集成测试 7/7 绿；
  本机 Chrome 浏览器验收（几何、移动端、搜索、键盘导航）见末节。
- **报告的总体判断是对的**：这不是玩具项目，治理与诚实声明较强，但还不能据此宣称生产就绪或承载真实资金。
  **同时它是一份旧快照**：若直接照单修，会修到已经修好的地方，也会漏掉今天真正的缺口。

## 一、报告哪些成立、哪些过期、哪些缺证

| 报告断言 | 本轮结论 | 证据/说明 |
|---|---|---|
| 工程治理与诚实声明较成熟 | **成立** | 多道 CI 门禁、JaCoCo/集成测试/许可头/调度锁/迁移/镜像基线等均真实存在 |
| `JdbcPaymentStateStore` 用 H2 专有 MERGE，PG 会炸 | **过期** | 当前实现走可移植 `JdbcUpsert`：先 UPDATE、未命中 `INSERT ... ON CONFLICT DO NOTHING`，并发再 UPDATE（`nexus-core/.../core/JdbcPaymentStateStore.java:103`、`nexus-core/.../db/JdbcUpsert.java:17-54`）。全仓没有 H2 `MERGE INTO` 实调用，只有注释解释为何不用它 |
| 缺省 DLQ 走 Kafka，裸跑无 Kafka 会踩坑 | **成立，且是代码事实** | `DeadLetterQueueService` 原 `@ConditionalOnProperty(..., matchIfMissing=true)`（:44），base `application.yml:425` 原 `store: kafka`。首次投递才阻塞/失败，属 fail-open 风险。**本轮已修**：base 默认 `NEXUS_WEBHOOK_DLQ_STORE:memory`，Kafka 显式 opt-in，`matchIfMissing=false`，并有 2 条钉子测试 |
| Seata 表 DDL 未入安装脚本 | **部分过期** | `nacos-config/seata-server-db.sql` 存在，`deploy/config/init-mysql-dbs.sh` 存在。不能笼统写「未入脚本」；真实环境是否执行到位仍需部署复验 |
| 支付扩展 13 写端点 501/5010，`processTransaction` 生产无调用点 | **成立（且是刻意保留）** | README:260；`PaymentTransactionProcessor.processTransaction` 存在，但扩展面仍是保留未启用，不是「已经修好」 |
| 桥存/提款/已提交根内存态，real-L1 未验证 | **成立** | PLAN-014:144、README:258。属真实未交付面，本轮没有把它写成已修 |
| PLAN-014「未做网络分区」 | **过期** | A-1/#733 已真跑：多数侧继续最终化、少数派冻结、治愈后 88 epoch 哈希零分歧。已在本轮把 PLAN-014 的边界改写为实测事实 |
| README ZK 一处真实 Groth16、另一处「依赖 Schnorr、不具备 ZK 安全属性」 | **成立（文档自相矛盾）** | 默认是远程 Groth16 fail-closed，Schnorr 只是未配置远程服务时的降级。已把两处歧义改成这个唯一口径（README:259、:300） |
| 前端无 CI 覆盖、设计令牌/透明度曾有缺陷 | **历史上成立，不能当现状** | 当前 `verify-build-artifacts.mjs` 已校验令牌注入、`var()` 闭环、alpha 合成；但它**明确不验** flex/居中/间距/断点/溢出/截图 diff（:157 起）。因此「几何/交互无证据」仍成立，「设计令牌没有保障」不成立 |
| 前端没有集成/E2E 证据 | **成立（本轮前）** | 21 个前端测试文件全是单测，BFF/后端测试为 0；没有 Playwright/Cypress/supertest。**本轮已补**：BFF 7 条真 HTTP 集成 + 4 条真浏览器用例 |
| 页面清单约 11 个 | **基本成立，现值 12 个** | `frontend/src/pages/` 当前 12 个 tsx（含 `ContractsPage`）。只有 6 个页面测试文件，不能写成「全部页面已覆盖」 |
| `styles/`、`index.css` 读不到 ⇒ 几何无从判断 | **表述不准确** | `styles/index.css` 确实不存在，但 `styles/tokens.css`、`tokens.ts` 存在且完整，是令牌真源；几何仍必须浏览器量取，不能靠读 CSS 推断 |
| Nacos standalone、Zipkin mem、Sentinel 健康探针留白 | **成立** | `docker-compose.prod.yml:562` `MODE=standalone`、:651 `STORAGE_TYPE=mem`、:667 起 Sentinel 仍无健康探针 |
| 压测/并发达标 | **不成立（证据不足）** | PR 只跑 5 VU 冒烟；full/capacity 需 staging 手动 dispatch，产物仅 14 天。没有长期 SLA 达标报表，不能判「高并发达标」 |
| 许可 166 个 Java 文件带 LGPL 头 | **数值需更新** | 当前 `nexus-core` 166 + `nexus-consortium` 4 = **170**。`docs/licensing.md` 仍写 166（162+4），是文档滞后；`--strict` 仍未开启，法务/许可未闭环 |
| 真实集群/staging/prod 可用 | **不成立** | `docs/ops/pending-conditional-2026-11-17.md` 明确：无 kind 集群、无 staging、无 `KUBE_CONFIG_PROD`；A6 真节点联调、A2 开闸三件、prod 部署均按条件冻结 |
| 「接口都正常、链路都通、对齐做好、并发达标」 | **不能由该报告推出** | 报告自己也声明没 clone/构建/浏览器/真实请求；这些话只能写「静态初评」，不能写成运行态结论 |

## 二、完善版结论（去莠存良）

### 1. 工程治理强，但「文档/实现一致性」仍是主要风险

仓里不是缺门禁，而是**门禁很厚、口径仍在漂移**：README 版本与 ZK 口径、PLAN-014 网络分区、
许可文件数、参考交付范围与真实 prod 编排并存多套叙述。正确顺序应是：
**先收敛事实口径 → 再补真实链路证据 → 最后谈生产交付**。本报告也需要被同一套纪律约束。

### 2. 真实资金面仍未闭合，不能拿沙箱/仿真当生产

保留未启用的支付扩展、桥经济链路内存态、real-L1 未验证、A2 开闸三件与 prod/staging 集群未到位，
都不是「小尾巴」，而是生产承载的硬边界。**本报告不应被引用为「可承载真实资金」的证据**；
当前最合适的定位仍是：**最小集私有化交付候选 + 沙箱可跑 + 部分最终性真证据**。

### 3. 前端从「单测有覆盖」跨到了「有真链路与几何证据」，但只完成了第一页

本轮前，前端与 BFF 只有 jsdom 单测，不能证明前后端联通，也不能证明布局正确。
本轮新增了可重复的真实链路：
`React 页面 → BFF（Express）→ core JSON-RPC（可控节点）`，以及 BFF 托管 dist 的真浏览器验收。
这证明的是 **HomePage 的首页链路**，不是 12 个页面全部完成，也不是生产网关资金链路已经联调。
拖拽、视觉回归、键盘全路径、可访问性、错误/空/无权限四态仍未系统覆盖。

### 4. 性能仍没有可引用结论

`full` / `capacity` 需要 staging + 凭证，PR 冒烟不能代表高并发。
所以只能说**压测框架与降级组件已具备，尚未留下可引用的 SLA 达标证据**；
报告中任何「高并发能力达标」表述都应降级为「待实测」。

### 5. 生产部署与许可仍是外部条件，不应被技术 PR 偷换

`KUBE_CONFIG_PROD`、staging/prod 集群、real-L1、法务许可结论、A2 开闸与 3 副本观察，
都需要 owner/客户侧输入。把它们登记为待决项比写成「快好了」更准确。

## 三、本轮已经落地的改进（可实证）

1. **DLQ 缺省改为内存 fail-safe**：无 Kafka 也不再隐式选中 Kafka；
   生产用 `NEXUS_WEBHOOK_DLQ_STORE=kafka` 显式启用，新增 2 条防回退测试（已通过）。
2. **PLAN-014 网络分区边界改写实**：补上 A-1 的真实分区判据与剩余 PG/多副本/real-L1 边界。
3. **README ZK 口径收敛**：默认远程 Groth16 fail-closed，Schnorr 仅降级；halo2/Plonk 仍冻结。
4. **BFF 可测化**：`createApp()` 与 `index.ts` 启动监听分离，路由行为不变；
   新增 7 条真实 HTTP 集成测试（状态、详情 404、地址、合约、跨链、SPA 深链、SSE）。
5. **浏览器验收新增**：真实 dist + BFF + 本机 Chrome，验首页数据渲染、
   桌面双栏几何（居中/同顶/同高/gap-6/无横溢出）、移动单栏、搜索与键盘导航。
   CORS 不是被 mock 掉，而是按真实部署源显式配置。
6. **修掉一条报告没写到的真实联调缺陷**：`index.html` 的 CSP `connect-src 'self' https:` 与
   `client.ts` 默认 `http://localhost:3000` 自相矛盾 ⇒ 页面能开、API 全被浏览器拦。
   现 BFF 默认同源相对 `/api`（Vite proxy / BFF 同源托管），CSP 仅把明确的本地 BFF/gateway
   开发源列入白名单，不放开任意 HTTP。这个 bug 单测/读 CSS 都看不出来，必须真浏览器才能发现。
7. **修掉移动端真实横向溢出**：390px 下页头 8 个导航图标总宽超出 22px；
   `hidden sm:inline` 只藏文字不藏图标。改为导航组 `flex-wrap justify-end`，保留全部入口、桌面不变；
   验收脚本实测 `MOBILE_OVERFLOW=[]`。浏览器最终 4/4 通过。

## 四、仍未完成 / 不应虚标为已解决

- **前端**：仅首页验收，未覆盖其余 11 页、拖拽/视觉回归/全键盘/可访问性/四态矩阵。
- **后端与业务链**：支付扩展、桥 real-L1、真实资金联调仍按原边界未闭合。
- **生产与合规**：staging/prod、`KUBE_CONFIG_PROD`、A2 开闸、real-L1、法务许可仍未到位。
- **性能**：full/capacity 未在 staging 实跑，未形成长期 SLA 报告。
- **部署编排**：Nacos standalone、Zipkin mem、Sentinel 健康探针、Seata DDL 的实际执行仍需部署复验。
- **许可**：当前源文件计 170 个 LGPL 头（文档仍写 166），`--strict` 未开启，不能宣称许可合规完成。

**底线**：本轮把「报告的旧快照」纠正成了当前事实，并补上了几处真实证据；
它**不是生产就绪声明**。剩下的外部条件与真实资金链路，必须逐项用运行态证据关闭，不能再用文档代替。

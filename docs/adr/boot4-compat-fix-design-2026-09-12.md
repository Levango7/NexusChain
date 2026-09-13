# Boot 4.0.8 兼容性修复设计 v2（backlog #10，2026-09-12）

> 目标：修复后网关 Nacos 服务发现可用、signing/wallet/bridge 全栈起通、
> Feign 服务互调闭环——作为全栈验证与生产部署的前置。
> v2 在 v1 基础上按"稳固收益/限制风险/兼顾全局"加固。

## 〇、取证补强（v2 新增）

1. **全局 nacos 配置位置（4 服务全在 bootstrap.yml）**：gateway /
   signing / wallet / bridge 的 `spring.cloud.nacos.*` 都只在 bootstrap.yml；
   api-gateway 的 nacos 只在 bootstrap（且无 application.yml 覆盖）；core
   完全无 nacos（local profile 直连 PG，不受影响）。→ 修复范围 = 4 业务服务，
   api-gateway 单独处理（是否启用 discovery 需确认）。
2. **application.yml 无 spring.cloud.nacos**：gateway 里的 `nacos:` 是业务
   开关 `nexus.nacos.enabled`（消费方待查——之前全库 grep 零命中，疑似死配置）。
3. **application.name 分布**：gateway/bridge 已有、signing/wallet 无（依赖
   bootstrap.yml 的 application.name）。→ 迁移时统一落 application.yml。
4. **bootstrap.yml 内容**：除 nacos + application.name 外无其他 bootstrap 专属
   配置 → nacos 迁出后 **bootstrap.yml 可整体退役**（不留双配置源）。
5. **Nacos 3.1.2 v1 cs/configs 假成功实锤**：GET 返回 404 "config data not
   exist"（identity 鉴权通过，HTTP 正常）——独立于 discovery 的缺陷，config
   写入须走 v3 admin API。

## 一、根因链（v1 结论维持）

10a/10c/10d 同族：**Boot 4 + Spring Cloud 2025 下 bootstrap context 默认关闭，
bootstrap.yml 的配置属性 main context 读不到** → nacos discovery 未装配
（`spring.cloud.nacos.*` 不可见）、Flyway/Tracer 自动配置断点（需 debug 验证
是版本还是条件）。

## 二、修复方案（按收益/风险/全局三维排布）

### 批次 A：10a 根修（最高收益、低风险、全局收编）—— nacos 配置迁 application.yml
- 4 服务把 `spring.cloud.nacos.discovery/config.*` 块从 bootstrap.yml 迁到
  application.yml 的 `spring:` 下；application.name 统一落 application.yml；
  bootstrap.yml 删除（整体退役）。
- 迁移语义：application.yml 是 main context 配置，nacos discovery 读到
  server-addr 即装配注册（不依赖配置中心，低耦合）。
- **全局联动**：api-gateway 一并评估（其 nacos 仅在 bootstrap——若需 discovery
  同迁，若走静态配置则删 bootstrap 保留静态路由）。

### 批次 B：10c/d 定位 + 修复（先确认再改，避免盲目升版）
- signing 开 debug 看 Flyway/Tracer 自动配置评估报告：
  - Flyway：`spring-boot-starter-flyway` 版本与 Boot 4.0.8 BOM 对齐检查；
    H2 mem 数据源装配条件验证。
  - Tracer：`SimpleTracingAutoConfiguration`/`BraveAutoConfiguration` 是否评估。
- 按 debug 结果：版本断点 → 升 Boot 4 兼容版本；条件未满足 → 补配置/Bean。

### 批次 C：部署资产（10b/10e/10f，独立低风险）
- 10b：4 服务 Dockerfile 补 dir-builder（/app/logs + signing 的 /app/leveldb
  + chown 65532），参照 nexus-core。
- 10e：signing/wallet/bridge dev profile 补 Seata exclude（对齐 gateway）。
- 10f：nacos-config/init.sh 重写 v3 API + identity 鉴权；config 写入走 v3。

## 三、验收标准（gateway discovery 为标尺）
1. gateway 重部署后 `/actuator/health` discoveryClient UP
2. Nacos v1 ns API `hosts` 非空
3. signing/wallet/bridge 起通，Feign 互调不再 fallback null
4. signing Flyway 执行 / Hibernate 表就绪；Tracer bean 注入成功
5. 全量测试 + CI（含 mpc-kind-smoke）绿

## 四、风险与回滚（v2 加固）

| 风险 | 缓解 |
|---|---|
| 配置迁移后 application.yml 与 nacos-config 配置中心合并语义变化 | discovery 不依赖配置中心，低风险；迁移只动 nacos 块，其余不动；单提交可回滚 |
| 删除 bootstrap.yml 影响未预见的 bootstrap 功能 | 已确认 bootstrap.yml 仅 nacos + application.name（无其他），退役安全 |
| 10c/d 升级依赖引入新问题 | 先 debug 确认根因再动；CI 覆盖构建+测试 |
| Feign 互调验证需要 signing/wallet 能起（10c/d/10e 前置） | 批次 A（discovery）与批次 B（signing 自启）独立验证；全链路验收放 C 之后 |
| 镜像重建成本 | 集中在批次 C 统一重建（~2-3min/个 ×4） |

## 五、实施顺序（批次 A → B → C，逐批验证提交）
1. 批次 A：nacos 迁移 + bootstrap 退役 → gateway 重部署 → discovery UP +
   注册表非空（标尺 1/2）
2. 批次 B：signing debug 定位 → 修复 → signing/wallet/bridge 自启（标尺 3/4）
3. 批次 C：Dockerfile 目录预建 + Seata dev 对齐 + init.sh v3 → 全链路
   Feign 互调验收（标尺 5）
4. 每批独立分支 squash 合并（沿用惯例）

## 六、待审核问题（v2 收敛）
1. **api-gateway 是否纳入 discovery**：其 nacos 仅在 bootstrap。若它是静态
   网关（不依赖服务发现）→ 只删 bootstrap 不影响；若要 discovery → 同迁。
   建议默认**只删 bootstrap、不动其路由**（最小改动，discovery 是增量需求）。
2. **10c/d 定位方式**：已定为"先 debug 确认再改"（稳、准）。
3. **bootstrap.yml 退役**：确认安全（见风险表），执行。
# 安全运维批设计（2026-09-14）——Trivy pcre2 修复 + 10b 目录预建 + 10f init.sh v3 + 10g k6 凭证

> 状态：设计完成，待审核通过后实施。
> 审核标准（用户四条）：收益性 / 风险可控 / 兼容可靠 / 可持续性。
> 事实来源全部为源码与 CI 实证（file:line 可查），演练报告不精确处已对账修正（见 §2.1）。

## 0. 背景与目标

master 631357a（PR #8 Boot4 兼容批）合并后，Security Scan 出现 3 个红项：

1. Trivy (mpc-engine)：libpcre2-8-0 CVE-2026-86145（越界写）+ CVE-2026-89161（内存损坏），HIGH×2，**Status=fixed**（10.42-1+deb12u1，CI run 34742569500 实测 Total: 2 (HIGH: 2)）
2. Trivy (zk-groth16-service)：同样这两个 pcre2 CVE（同一 Trivy DB 漂移新增，本地预飞 DB 2026-09-04 时还没有）
3. OWASP Dependency-Check：NVD_API_KEY 缺失（用户操作项，本批不涉及）

结合 backlog 10 的剩余项（10b/10f/10g），本批一次收口 4 项代码改动 + 1 项演练验证。

## 1. 事项清单与改动点

### 1.1 【A】pcre2 CVE 修复（2 Dockerfile）

**事实**：
- mpc-engine/Dockerfile:41-46、zk-groth16-service/Dockerfile:14-15 运行时阶段均为 `debian:bookworm-slim`，现有 `apt-get update && apt-get install ca-certificates`
- 两个 CVE 的修复版本 `libpcre2-8-0 10.42-1+deb12u1` 已在 bookworm 仓库 point release 中
- 基础镜像 `debian:bookworm-slim` 无 digest 钉版，每次构建拉最新；但 base 镜像重建滞后 DSA 数天到数周——这个窗口期内 Trivy DB 已收录新 CVE 而镜像包未更新（本次红即此窗口）

**改动**：两个 Dockerfile 运行时阶段 `apt-get update` 后补 `apt-get upgrade -y`（全量 point-release 升级），注释注明闭窗原理与 CVE 编号。

**为什么全量升级而非只升 pcre2**：只升单包 = 下一个 CVE 再改一行（跑步机）；全量升级让"重建镜像"本身成为持续闭环——任何 base 包的新修复在下次构建自动生效。bookworm stable point release 保守（同 ABI），Rust 二进制 glibc 动态链接兼容（同 bookworm 系）。

### 1.2 【B】10b 镜像目录预建（5 Dockerfile）

**事实对账（源码为准，修正演练报告的 /app/logs 记载）**：

| 服务 | 实际需要的目录 | 证据 |
|---|---|---|
| nexus-core | **/app/logs**（现有 dir-builder 只有 leveldb+data，缺 logs） | nexus-core/nexus-core/src/main/resources/logback.xml:18 `./logs/nexuscore.log` 无 profile 守卫 + WORKDIR /app；演练 CrashLoop 实证 |
| nexus-gateway | **/var/log/nexus** | logback-spring.xml:23,40（prod profile JSON+audit） |
| nexus-signing-service | **/var/log/nexus + /app/leveldb** | logback-spring.xml:38,60 + storage/Leveldb.java:40 `user.dir/leveldb`（@PostConstruct 无条件） |
| nexus-wallet-service | **/var/log/nexus** | logback-spring.xml:22 |
| nexus-bridge | **/var/log/nexus** | logback-spring.xml:22 |

演练报告 §#1 的"gateway 缺 /app/logs"记载不精确：gateway logback（dev=console、prod=/var/log/nexus）从不写 /app/logs；写 ./logs 的是 core。drill yaml 给 gateway 挂 /app/logs emptyDir 是无效但无害的防御。**本批按源码真相修，Phase B 演练实证。**

**改动**：参照 nexus-core/Dockerfile:81-105 既有 dir-builder 模式（busybox 中间阶段 mkdir + `COPY --chown=nonroot:nonroot --from=dir-builder`）：
- core：既有 dir-builder 补 /app/logs（1 行）
- gateway/signing/wallet/bridge：新增 dir-builder 阶段 + COPY（每文件 ~6 行）
- signing 的 /app/leveldb 与 gateway 等的 /var/log/nexus 一并预建

**K8s 关系**：emptyDir/hostPath 挂载会遮盖镜像内目录（挂载语义如此，非缺陷）；镜像预建解决的是**无挂载场景**（docker run、compose、以及忘记挂卷的生产配置）——演练报告所说"emptyDir 只是绕过"即此。

### 1.3 【C】10f nacos-config/init.sh v3 重写（1 脚本）

**事实**：
- 现脚本全 v1 API（init.sh:37,53,57,76,100）：v1 console API 在 Nacos 3.1.2 全 410（演练发现 #6）
- 演练实测可用 v3 形状（F:\Nexus\build\nacos-init-v3.sh，kind 集群验证过）：
  - probe：`GET /nacos/v3/admin/core/state`（CI nacos job 同款）
  - namespace：`GET /v3/admin/core/namespace/list` + `POST /v3/admin/core/namespace`（namespaceId/namespaceName/namespaceDesc）
  - 配置发布：`POST /v3/admin/core/config`（**dataId/groupName/namespaceId**——注意 v3 参数名与 v1 的 group/tenant 不同）
  - 鉴权：`nexus-identity: nexus-dev` header（identity key/value）
- v1 cs/configs 有"假成功"（返回 true 读回 404）→ v3 重写必须带**发布后读回校验**
- **现脚本还有一个隐性 bug**：shared 配置发布到 ns=public（init.sh:69 默认），但服务端 shared-configs 读的是 `${NEX_NACOS_NAMESPACE:dev}`（4 服务 application.yml）——即发布到服务读不到的命名空间；演练 v3 脚本改发 dev 后 gateway 才读到

**改动**：init.sh 全量重写（保持 bash+curl+`set -euo pipefail`，同目录同文件名）：
1. 就绪探测 → v3 state 端点
2. namespace：幂等创建 dev/test（list→POST）
3. 发布：nexus-common / nexus-sentinel-rules / nexus-seata + seataServer.properties（SEATA_GROUP，properties，ns=public 与 Seata server 对齐）+ 4 服务占位
4. **每个配置发布后 GET 读回校验**（治"假成功"）
5. shared/服务配置发到 **dev 与 test 两个 ns**（服务实际读取的 ns）
6. identity header 参数化：`NACOS_IDENTITY_KEY`（默认 nexus-identity）/`NACOS_IDENTITY_VALUE`（默认 nexus-dev）；值为空则不带 header（兼容 Nacos 2.x / 无鉴权 dev compose）

### 1.4 【D】10g k6 压测凭证预置（1 compose 值 + 1 脚本）

**事实**：
- k6 业务场景需 API_KEY + SIGNING_SECRET（perf/k6/utils/auth.js:32-33，HMAC 头 X-NexusChain-ApiKey/Timestamp/Nonce/Signature）
- 供给链：`POST /api/v1/merchants/register`（公开）→ `POST /{id}/verify` + `POST /{id}/api-keys`（**ADMIN JWT**，MerchantController.java:51,65）；api-keys 返回 `{apiKey, secret}`（:66-71，仅此一次）
- gateway ADMIN JWT：JwtTokenProvider.generateToken（HS256，roles claim 逗号分隔，注释明言"供测试与运维签发流程使用"）；密钥 `nexus.security.jwt.secret`——**未配置时随机密钥 fail-closed**（JwtTokenProvider.java:64-73）→ 离线签发要求 dev 环境用确定性密钥

**改动**：
1. docker-compose.yml（dev 栈）：gateway 环境补确定性 dev JWT 密钥（值标注 DEV-ONLY；若现 env 名缺失则一并补绑定，实施时核实 application.yml 的 `${JWT_SECRET:...}` 映射）
2. 新增 `perf/k6/seed-perf-credentials.ps1`（+ 等价 .sh）：
   - 用 dev JWT 密钥离线签发 ADMIN token（HS256，roles=ADMIN）
   - register → verify → api-keys 三连
   - 输出 API_KEY/SIGNING_SECRET 与现成的 `k6 run -e API_KEY=… -e SIGNING_SECRET=…` 命令
   - 不改任何 Java 代码 / DB schema——零 schema 耦合，任何环境只要配了已知密钥即可用

### 1.5 【E】Phase B：kind 演练重跑（无代码改动，验证闭环）

- 前置：Docker Desktop + kind nexus-deploy-drill（资产 F:\Nexus\build\：kind.exe/helm/tgz/4 个 yaml/Dockerfile.java-drill）
- 验收清单：
  1. gateway Discovery Client initialized + Nacos 注册表非空（10a 修复验收标尺）
  2. **Feign 服务名互调真值**：gateway→signing 真实调用成功（Boot4 批的最终验收，此前只验到"编译+启动"层）
  3. 去掉 drill yaml 的 logs/leveldb emptyDir workaround → 服务不 CrashLoop（10b 实证；drill 用 Dockerfile.java-drill 同步加 dir-builder）
  4. init.sh v3 全绿（含读回校验）
  5. seed-perf-credentials 跑通并打一发真实 k6 冒烟（10g 实证）
- 产出：docs/deploy/fullstack-drill-report-2026-09-14.md（增量报告）

## 2. 四标准审核裁决

| 项 | 收益 | 风险 | 兼容 | 可持续 | 裁决 |
|---|---|---|---|---|---|
| A pcre2 | 2 服务 4 个 HIGH 清零，Security Scan 收 2 红 | 低——bookworm point release 同 ABI；构建时间 +~20s/镜像 | 高——不改应用层 | 高——重建即闭环，无跑步机 | ✅ 做（全量升级方案） |
| B 目录预建 | 消灭无挂载场景 CrashLoop（演练实证类问题） | 低——加目录不改行为；挂载场景零影响 | 高——core 模式已在生产镜像验证 | 高——模式可复制 | ✅ 做（按源码真相的目录清单） |
| C init.sh v3 | Nacos 3.x 唯一可用初始化路径 + 治假成功 | 低——脚本重写，不影响服务端；保留无鉴权兼容 | 高——v3 API 形状是演练实测的 | 高——参数化 identity，Nacos 2.x/3.x 双兼容 | ✅ 做 |
| D k6 凭证 | 本地/演练压测自助化，消除手动造凭证 | 低——dev-only 密钥值（compose 既有惯例）；脚本只调公开+既有端点 | 高——不改 schema/代码 | 高——离线签发方案不依赖任何服务端改造 | ✅ 做 |
| E 演练重跑 | 10a+10b+10f+10g 的实证闭环，Feign 互调首验 | 低——演练环境独立 | 高 | 高——报告固化为资产 | ✅ 做（Phase B，合并后） |

**明确不做（本次）**：
- zk-groth16-service 补 USER nonroot：收益真实（现以 root 运行）但 `/setup` 卷挂载的属主语义会被改变（Dockerfile:4 文档用法 `-v <setup-vol>:/setup`），破坏已文档化用法违反"兼容可靠"；**另立 backlog 项**，独立评估
- builder 阶段升级：Trivy 只扫运行时镜像，改 builder 是纯成本
- mpc-engine/zk 迁 distroless：Rust+glibc 运行时迁移是大改，与"风险可控"冲突；等 Rust distroless 方案成熟单独立项

## 3. 实施与验证

- 分支：`fix/security-ops-batch-2026-09-14`（A-D 一个 PR）
- 本地验证：
  - A/B：`docker build` 两个 Rust 镜像 + 本地 trivy 扫描（镜像内 `dpkg -l libpcre2-8-0` 验版本 ≥10.42-1+deb12u1）；Java 服务镜像构建成功 + `docker run --rm --entrypoint ls` 验目录存在与属主（distroless 无 shell，用 busybox 探针镜像验证属主 65532）
  - C：bash -n 语法 + 本地 Nacos（若有）冒烟；无则 Phase B 首验
  - D：脚本对本地 compose 栈实跑（或语法级验证 + Phase B 首验）
- CI：全绿含 Security Scan（pcre2 两红应收绿；OWASP 红为用户操作项不在验收内）
- 合并：squash merge（既定授权）

## 4. 回滚

A/B：revert Dockerfile 即可（镜像重建回到现状）；C：git revert（v1 脚本在历史）；D：compose 值与脚本独立可撤。

# Phase B 本机剩余项收口（2026-11-23）

## 新确认并修复的缺陷

### 1. 全局验签配置键不一致

`application.yml` 使用 `nexus.security.request-signing-secret`，interceptor 的 `@Value`
却读取 `nexus.security.requestSigningSecret`。`@Value` 不做 relaxed binding，导致 base/dev
即使正确注入 env 也拿到空串，返回 40105。

修复：标准 kebab 键优先，旧 camelCase 兼容回退。新增真实 Spring `ApplicationContextRunner`
三例（标准键、兼容键、优先级）；修前标准键/优先级两例红，修后 16 项 interceptor 测试全绿。

### 2. seed 输出的 k6 密钥必然不匹配

seed 原把 api-keys 返回的商户 secret 作为 k6 `SIGNING_SECRET`，但当前服务端只接受全局
`request-signing-secret`，商户 secret 只存哈希、不参与验签。因此即使修好属性键，原输出
命令仍会从 40105 变成 40107。

修复：bash/PS1 均可通过第三参数或 `NEXUS_REQUEST_SIGNING_SECRET` 接收全局密钥，输出与
k6 命令改用该值；缺少时 fail-fast，不打印误导性命令。

### 3. discovery 双网卡可能广告不可达地址

signing 同时连接 default 与 `nexus-net`，曾向 Nacos 注册 `192.168.0.2`，gateway 却在
`172.31.0.x`，出现「Nacos 注册成功、Feign fallback、statusCode=null」。

修复：Nacos/gateway/signing/wallet/bridge 都同时连接两张网络，任何一张被广告都可被其他
参与者路由。共享网络后真实 Feign 重新为 UP。

## #25 本机五项证据

1. Nacos 注册表非空，gateway/signing 均注册成功。
2. gateway 经 Feign 调 signing capability，signing 组件 UP。
3. gateway/signing 的 mounts 不含 `/app/logs`、`/var/log/nexus`、`/app/leveldb`，仍正常启动。
4. Nacos init 鉴权、发布、读回与重启幂等通过。
5. seed 三连 + 真实 k6 payment-create：
   - 最终复跑 `biz_success_rate=143/143=100%`、`checks=572/572=100%`、HTTP 失败 0；
   - 最大请求耗时 22.96ms，全部原阈值通过；另两次独立复跑分别为 139/139（max 32.65ms）
     与 138/138（max 36.77ms）；
   - PowerShell seed 输出与 bash 相同的全局验签契约；
   - gateway `test + integrationTest + spotbugsMain` 全量 `BUILD SUCCESSFUL`；
   - 最终容器与网络零残留。

## 装置问题与产品问题的分界

- 缺少 core 会产生连接超时，现由明确标注的只读 fake chain RPC 模拟；不用于真实链证明。
- 5 VU 超单 key 300/min 限流会产生 429；正式功能冒烟降为 1 VU，不提高限流，不放宽阈值。
- 预热仍要求 HTTP 失败为 0；冷启动不作为终验，正式阶段独立执行全部阈值。

## 边界

#25 的五项**本机 Docker 切片**均已完成。kind 完整 Phase B、staging/prod、real-L1、真实 MPC
签名、真实资金、容量压测与长期 SLA 仍未闭环。本记录不替代这些外部验收。

# Feign capability 与 Nacos 鉴权修复记录（2026-11-23）

## 确认缺陷

### 1. 非 Helm 默认路径缺少 capability 所需角色

- gateway 默认 `JWT_SERVICE_ROLES=SIGNING_SERVICE`；signing 将其归一为 `SIGNER`。
- 但 capability 明确要求 `READ`，所以默认服务发现/Feign 即使全部正常，探针仍被 403 拒绝。
- Helm deployment 已显式补过 `SIGNING_SERVICE,READ`，application 默认值与 dev compose 未同步，
  因而 Helm 的修复没有覆盖其它部署路径。原健康指示器单测 mock Feign，无法发现此断链。

### 2. Nacos 初始化存在鉴权顺序与假成功

- 旧顺序先 namespace/config、后 admin 引导；Nacos 启动早期匿名窗口关闭后会随机得到 403。
- admin 引导后未登录，后续请求没有 accessToken，可能遇到鉴权传播竞态。
- 引导请求失败也曾无条件打印「初始化完成」，无法证明凭据可用。
- 修复为 admin 引导 → 每次登录校验 → 后续 admin API 携带 Bearer；登录失败在任何写入前退出。

## 修复

- application 默认角色改为 `SIGNING_SERVICE,READ`，保留旧签名能力，仅增加只读探针权限；
  Helm 现有显式值不变。
- Nacos init 调整鉴权顺序、登录验证、Bearer 传递、namespace JSON 空白兼容与 HTTP 失败诊断。
- 新增 `FeignJwtRequestInterceptorTest`：解析真实 application.yml，并实际签发/解析 Feign JWT。
- 新增 `nacos-config/test_init_auth.py`：合成 Nacos 强制未登录 403，覆盖顺序、幂等与失败中止，
  已接入 Build & Test。
- 新增 `scripts/drill/feign_capability_drill.py` 与演练说明：隔离容器、动态端口、随机凭据、
  正反鉴权、Nacos 注册和真实 Feign 组件判定。

## 证据

- 修复前：两个 Java 回归均失败；真实 signing 上 legacy-only token=403，加入 READ 后=200。
- 修复后：专项 Gradle `BUILD SUCCESSFUL`（两项回归）；gateway `test + integrationTest + spotbugsMain`
  全量 `BUILD SUCCESSFUL`（13m35s）。
- 真实 Docker 修后链：
  - Nacos 初始化与读回通过，重启后幂等通过；
  - signing/gateway 均注册成功；
  - 匿名 403、legacy-only 403、READ 200；
  - gateway signing 组件 **UP**，即 Nacos discovery + Feign + JWT 全链成功。
- 合成 Nacos：正向/幂等/登录失败中止全过；将 namespace 移回登录之前的反向变异精确转红，
  还原 sha256=`81759662e8c94fb937cb451b1f7ab8e51dd93ff7010467669cdd1d02f52aff11` 后复绿。
- 宿主 Git Bash/WSL 的 HCS 超时与路径问题均作为装置问题排除；最终初始化在 Nacos 容器内执行，
  不把这些失败计作产品缺陷或通过证据。

## 未关闭边界

本交付只关闭本机 Nacos/Feign 切片。未重跑 kind、未执行完整 Phase B 的镜像目录与 k6 凭证项，
不代表 staging/prod、K8s HA、真实 MPC 签名、真实资金或长期 SLA 已验收。

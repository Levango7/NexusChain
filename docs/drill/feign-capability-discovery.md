# Nacos + Feign capability 隔离演练（2026-11-23）

## 目的与边界

验证 gateway 通过 Nacos 服务名发现 signing-service，并由 Feign JWT 调用只读
`GET /api/v1/transfers/capability`。这是**真实 Docker 容器、真实 Java 服务、真实 Nacos**，
不是 mock；同时不是 Kubernetes、staging、生产或真实资金验收。

演练使用隔离 compose project、动态宿主端口、一次性 Nacos/JWT/MPC KEK 凭据，结束时自动
删除容器与卷。 signing 的分布式 MPC 和 CGGMP 仅在本只读探针中关闭；MPC KEK 启动校验保持
开启，不触发签名、广播或资金动作。

## 运行

```bash
python3 scripts/drill/feign_capability_drill.py
```

成功必须看到：

1. Nacos v3 初始化与配置读回通过；重启后再次初始化仍幂等。
2. signing/gateway 容器收到与生成值逐字一致的凭据（只比较，不打印）。
3. 两个服务均有 Nacos instance。
4. 匿名 capability 请求为 403。
5. `SIGNING_SERVICE` 单独角色为 403；加入 `READ` 后为 200 / statusCode=2000。
6. `/actuator/health` 的 signing 组件为 **UP**，证明 gateway 经 Feign 服务名调用成功。

整体 `/actuator/health` 可能因本演练刻意不启动 core 或其它 connector 而为 DOWN；判据只取
signing 组件，不能拿整体状态替代该组件，也不能把其它 connector 的 DOWN 写成 Feign 失败。

## 旧默认复现 / 变异模式

```bash
python3 scripts/drill/feign_capability_drill.py --expect legacy-down
```

此模式仅用于修复前代码或反向变异：默认 token 只有 `SIGNING_SERVICE` 时，signing 组件应为
DOWN。修复后的代码应使用默认 `--expect up`，不要期待它继续失败。

## 环境与安全实现

- Nacos 初始化在 Nacos 容器内执行，使用镜像自带 bash/curl；不依赖宿主 Git Bash/WSL。
- Nacos auth token 使用标准 Base64；admin 密码含大小写字母和数字；JWT/KEK 均由随机源生成。
- 容器内凭据断言不输出值；诊断日志对本次生成的秘密做脱敏。
- Nacos 初始化先完成 admin 引导与登录，再在后续 admin API 请求中携带 Bearer accessToken。
- 初始化、构建、等待或清理失败都应返回非零；不得把未执行到 Feign 的装置失败写成 PASS。

2026-11-23 本机修后证据见 `docs/audit/feign-nacos-auth-remediation-2026-11-23.md`。

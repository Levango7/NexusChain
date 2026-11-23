# seed-perf + k6 真实业务冒烟（2026-11-23）

## 运行

```bash
python3 scripts/drill/perf_business_drill.py
```

脚本构建并启动隔离 gateway，执行现有 seed 的 register → ADMIN verify → api-keys 三连，
再以 k6 运行真实 `POST /api/v1/payments`。Windows 上还会实际执行 PowerShell seed，核对
bash/PS1 都输出传入的全局验签密钥。

## 判据

- gateway 在没有 `/app/logs`、`/var/log/nexus` 挂载时为 UP。
- k6 正式阶段 1 VU，避免单 API key 的 300 次/分钟限流；不提高限流配置。
- 预热只用于消除冷启动，仍要求 HTTP 失败为 0；正式阶段执行原业务与 P99<500ms 阈值。
- 当前验收：`biz_success_rate=100%`、`checks=100%`、`http_req_failed=0%`。
- seed 输出 `SIGNING_SECRET` 必须是 `NEXUS_REQUEST_SIGNING_SECRET`，不是 api-keys 返回的
  商户 secret。

## 模拟条件与边界

- `scripts/drill/fake_chain_node.py` 在隔离网络内提供高度、交易详情/确认数和 NOT_ACTIVE
  最终性响应。它只消除不存在 core 时的连接超时，让 gateway 走真实 HTTP 客户端与确认回退；
  **不是**真实链、共识或 real-L1 证明。
- `NEXUS_REQUEST_SIGNING_SECRET`、JWT_SECRET 由随机源生成；seed 的 API key/secret 只保存在
  进程内，输出脱敏，结束后删除容器、网络与卷。
- 正式阶段是低负载功能冒烟，不是容量基线。单 key 429 属于既有防刷语义，不能用调高限流
  或放宽阈值来制造通过。
- 本结果不代表 kind、staging/prod、真实链、真实资金、完整 k6 四场景或高并发 SLA。

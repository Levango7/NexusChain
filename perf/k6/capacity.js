/**
 * NexusChain k6 容量画像（Capacity Profile）— 2026-10-03
 * ============================================================================
 * 与 payment-create.js 的区别：那是「定负载验证 SLA」（1000 VU、P99<500ms 是否达标），
 * 这是「找拐点」（RPS 阶梯爬升，观察 P99/P95 与错误率在哪里开始劣化——容量上限在哪）。
 *
 * 执行器：ramping-arrival-rate（按 RPS 供压，VU 自动扩缩）——比 VU 驱动更适合
 * 容量画像：被测系统过载时表现为延迟劣化/错误率上升，而不是客户端 VU 数到顶。
 *
 * 阶梯（环境变量驱动，默认 6 级 × 1 分钟，50 RPS 起步、步进 50）：
 *   50 → 100 → 150 → 200 → 250 → 300 RPS，末级后 15s ramp-down
 *   调法：CAP_START_RPS / CAP_STEP_RPS / CAP_STEP_COUNT / CAP_STEP_DURATION
 *
 * 阈值刻意宽松（rate<0.10 / checks>0.90）——容量画像是**探测**不是**验收**，
 * 过载段出现劣化是数据不是失败；拐点从 summary 的各阶梯 P95/P99/错误率读出。
 *
 * 关于「50 万次/秒」：该量级 = 双 11 级（支付宝 2019 峰值 54.4 万笔/秒，
 * OceanBase TPC-C 7.07 亿 tpmC），需要分布式压测集群（多台压测机）+
 * 生产级多可用区部署才构成有效测量；单 runner 的合理探测范围是
 * 数百到低千 RPS（客户端 CPU/带宽先于服务端成为瓶颈）。本脚本的价值
 * 是在小规模画出拐点形状（P99 曲线弯在哪），拐点的**成因**（DB/线程池/
 * GC/连接池）再由服务端指标定位——方法论可线性外推到更大阶梯。
 *
 * 运行（需 PERF_API_KEY / PERF_SIGNING_SECRET——业务路径才有测量意义）：
 *   k6 run -e API_KEY=xxx -e SIGNING_SECRET=yyy -e BASE_URL_GATEWAY=... \
 *          --summary-export=capacity-summary.json perf/k6/capacity.js
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

import { buildAuthHeaders, assertCredentials } from "./utils/auth.js";
import { paymentCreateBody } from "./utils/data.js";

// ---------------------------------------------------------------------------
// 配置（环境变量）
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL_GATEWAY || "http://localhost:8080";
const ENDPOINT = "/api/v1/payments";
const TIMEOUT_MS = parseInt(__ENV.TIMEOUT_MS || "10000", 10);

// 阶梯参数
const START_RPS = parseInt(__ENV.CAP_START_RPS || "50", 10);
const STEP_RPS = parseInt(__ENV.CAP_STEP_RPS || "50", 10);
const STEP_COUNT = parseInt(__ENV.CAP_STEP_COUNT || "6", 10);
const STEP_DURATION = __ENV.CAP_STEP_DURATION || "1m";
const WARMUP_DURATION = __ENV.CAP_WARMUP_DURATION || "30s";
const COOLDOWN_DURATION = __ENV.CAP_COOLDOWN_DURATION || "15s";

// 按 RPS 阶梯构造 ramping-arrival-rate stages
function buildStages() {
  const stages = [];
  // 预热：起始速率稳定段（排除 JIT/连接池/缓存冷启动噪声）
  stages.push({ duration: WARMUP_DURATION, target: START_RPS });
  for (let i = 1; i <= STEP_COUNT; i++) {
    stages.push({ duration: STEP_DURATION, target: START_RPS + i * STEP_RPS });
  }
  // 收尾降载
  stages.push({ duration: COOLDOWN_DURATION, target: 0 });
  return stages;
}

const bizSuccessRate = new Rate("biz_success_rate");
const capacityLatency = new Trend("capacity_req_latency", true);

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
  tags: { scenario: "capacity", service: "nexus-gateway" },

  scenarios: {
    capacity: {
      executor: "ramping-arrival-rate",
      startRate: START_RPS,
      timeUnit: "1s",
      preAllocatedVUs: parseInt(__ENV.CAP_PRE_ALLOC_VUS || "100", 10),
      maxVUs: parseInt(__ENV.CAP_MAX_VUS || "400", 10),
      stages: buildStages(),
    },
  },

  // 宽松护栏：容量画像允许过载劣化；只挡「系统性崩坏」（错误率>10%）
  thresholds: {
    http_req_failed: ["rate<0.10"],
    checks: ["rate>0.90"],
  },

  noConnectionReuse: false,
  insecureSkipTLSVerify: __ENV.TLS_SKIP_VERIFY === "true",
};

// ---------------------------------------------------------------------------
// 主循环
// ---------------------------------------------------------------------------
export function setup() {
  assertCredentials();
}

// 多密钥轮换（2026-10-03）：dev 的 InMemoryRateLimiter 为 300 次/分钟/API key
// 硬编码——单 key 仅 5 rps，容量阶梯必须跨多 key 摊薄。API_KEYS 为逗号分隔的
// 密钥列表（seed-multi-keys.ps1 批量签发）；签名 canonical 不含 API key，
// 故可对 buildAuthHeaders 产出的头安全覆写 X-NexusChain-ApiKey。
// 未提供 API_KEYS 时退化单 key（env API_KEY）。
const API_KEYS = (__ENV.API_KEYS || "").split(",").filter((s) => s.length > 0);
const MY_API_KEY = API_KEYS.length > 0 ? API_KEYS[useIndex(__VU, API_KEYS.length)] : "";

function useIndex(vu, len) {
  return ((vu - 1) % len + len) % len;
}

export default function () {
  // 请求格式严格镜像 payment-create.js：body 必须 JSON.stringify 后
  // 同时用于 HMAC 签名与发送（对象直传会被 k6 当 form 编码——2026-10-03
  // 首跑由此 100% 请求被拒，修正后重跑）。
  const bodyStr = JSON.stringify(paymentCreateBody());
  const headers = buildAuthHeaders("POST", ENDPOINT, bodyStr);
  if (MY_API_KEY) {
    headers["X-NexusChain-ApiKey"] = MY_API_KEY;
  }
  const res = http.post(`${BASE_URL}${ENDPOINT}`, bodyStr, {
    headers: headers,
    timeout: TIMEOUT_MS,
    tags: { name: "capacity_payment_create" },
  });

  const ok = res.status >= 200 && res.status < 300;
  check(res, {
    "http 2xx/3xx": ok,
    "有 paymentId 或业务字段": ok,
  });
  bizSuccessRate.add(ok);
  capacityLatency.add(res.timings.duration);

  sleep(0.05); // 50ms 轻思考时间，避免纯无思考压测放大连接复用
}

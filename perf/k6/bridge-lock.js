/**
 * NexusChain k6 压测 — 跨链桥锁定
 * ============================================================================
 * 目标：P99 < 2s @ 100 RPS
 * 端点：POST /api/v1/bridge/lock
 *
 * 桥锁定涉及验证者多签 + 源链确认，延迟显著高于支付创建，故阈值放宽到 2s。
 *
 * 运行：
 *   k6 run -e API_KEY=xxx -e SIGNING_SECRET=yyy \
 *          -e BASE_URL_BRIDGE=http://localhost:8084 \
 *          perf/k6/bridge-lock.js
 */

import http from "k6/http";
import { check, sleep, group } from "k6";
import { Rate, Trend } from "k6/metrics";

import { buildAuthHeaders, assertCredentials } from "./utils/auth.js";
import { bridgeLockBody, thinkTime } from "./utils/data.js";

// ---------------------------------------------------------------------------
// 配置
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL_BRIDGE || "http://localhost:8084";
const ENDPOINT = "/api/v1/bridge/lock";
const TIMEOUT_MS = parseInt(__ENV.TIMEOUT_MS || "15000", 10); // 桥延迟高，放宽到 15s

// 自定义指标
const lockLatency = new Trend("bridge_lock_latency", true);
const bizSuccessRate = new Rate("biz_success_rate");

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
  tags: { scenario: "bridge-lock", service: "nexus-bridge" },

  stages: [
    { duration: "30s", target: 20 },
    { duration: "1m", target: 50 },
    { duration: "2m", target: 100 },
    { duration: "30s", target: 0 },
  ],

  thresholds: {
    http_req_duration: ["p(99)<2000"],
    http_req_failed: ["rate<0.01"],
    checks: ["rate>0.99"],
    biz_success_rate: ["rate>0.99"],
    bridge_lock_latency: ["p(99)<2000"],
  },

  noConnectionReuse: false,
  insecureSkipTLSVerify: __ENV.TLS_SKIP_VERIFY === "true",
};

// ---------------------------------------------------------------------------
// setup
// ---------------------------------------------------------------------------
export function setup() {
  assertCredentials();
  // 健康探测：bridge /status
  const resp = http.get(`${BASE_URL}/api/v1/bridge/status`, {
    timeout: TIMEOUT_MS,
    tags: { phase: "setup" },
  });
  if (resp.status !== 200) {
    console.warn(`setup: bridge /status returned ${resp.status}`);
  }
  return { baseUrl: BASE_URL };
}

// ---------------------------------------------------------------------------
// 主迭代
// ---------------------------------------------------------------------------
export default function (data) {
  const baseUrl = data.baseUrl;
  const body = bridgeLockBody();
  const bodyStr = JSON.stringify(body);
  const headers = buildAuthHeaders("POST", ENDPOINT, bodyStr);
  const url = `${baseUrl}${ENDPOINT}`;

  group("bridge_lock", function () {
    const resp = http.post(url, bodyStr, {
      headers: headers,
      timeout: TIMEOUT_MS,
      tags: { endpoint: "bridge_lock" },
    });

    lockLatency.add(resp.timings.duration);

    const ok = check(
      resp,
      {
        "status is 201": (r) => r.status === 201,
        "has tx id": (r) => {
          try {
            const o = JSON.parse(r.body);
            return o && (o.id || o.txId || o.transactionId) !== undefined;
          } catch (e) {
            return false;
          }
        },
        "response time < 2s": (r) => r.timings.duration < 2000,
      },
      { endpoint: "bridge_lock" }
    );

    bizSuccessRate.add(ok && resp.status === 201);

    if (!ok && Math.random() < 0.01) {
      console.warn(
        `bridge_lock FAIL: status=${resp.status} body=${resp.body}`
      );
    }
  });

  // 桥锁定思考时间 200~500ms（模拟链上确认间隔）
  sleep(thinkTime(200, 500) / 1000);
}

export function teardown(data) {
  console.log(`bridge-lock teardown: baseUrl=${data.baseUrl}`);
}
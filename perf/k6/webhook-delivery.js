/**
 * NexusChain k6 压测 — Webhook 投递
 * ============================================================================
 * 目标：P99 < 1s @ 500 RPS
 * 端点：POST /api/v1/payments/{id}/confirm（模拟链上事件回调触发 webhook 投递）
 *
 * 流程：
 *   1. setup 阶段创建 N 条 SUCCEEDED 支付，得到 id 池
 *   2. 每个 VU 随机取 id，发起 confirm（携带随机 chainTxHash）
 *   3. 服务端收到 confirm 后会触发 webhook 投递到 notify_url
 *      （压测时 notify_url 指向 mockbin / 本地 echo 服务）
 *
 * 注：本脚本压测的是"网关接收回调 → 触发 webhook 投递"链路；
 *     若需端到端压测 webhook 接收方，请单独部署 echo 服务并设 NOTIFY_URL。
 *
 * 运行：
 *   k6 run -e API_KEY=xxx -e SIGNING_SECRET=yyy \
 *          -e BASE_URL_GATEWAY=http://localhost:8080 \
 *          -e SEED_POOL_SIZE=100 \
 *          perf/k6/webhook-delivery.js
 */

import http from "k6/http";
import { check, sleep, group } from "k6";
import { Rate, Trend, Counter } from "k6/metrics";

import { buildAuthHeaders, assertCredentials } from "./utils/auth.js";
import {
  paymentCreateBody,
  webhookConfirmBody,
  extractPaymentId,
} from "./utils/data.js";

// ---------------------------------------------------------------------------
// 配置
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL_GATEWAY || "http://localhost:8080";
const PAYMENTS_ENDPOINT = "/api/v1/payments";
const TIMEOUT_MS = parseInt(__ENV.TIMEOUT_MS || "10000", 10);
const SEED_POOL_SIZE = parseInt(__ENV.SEED_POOL_SIZE || "100", 10);

// 自定义指标
const webhookLatency = new Trend("webhook_delivery_latency", true);
const bizSuccessRate = new Rate("biz_success_rate");
const seedCreateCount = new Counter("seed_create_count");

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
  tags: { scenario: "webhook-delivery", service: "nexus-gateway" },

  stages: [
    { duration: "30s", target: 100 },
    { duration: "1m", target: 300 },
    { duration: "2m", target: 500 },
    { duration: "30s", target: 0 },
  ],

  thresholds: {
    "http_req_duration{endpoint:webhook_confirm}": ["p(99)<1000"],
    "http_req_failed{endpoint:webhook_confirm}": ["rate<0.01"],
    "checks{endpoint:webhook_confirm}": ["rate>0.99"],
    biz_success_rate: ["rate>0.99"],
    webhook_delivery_latency: ["p(99)<1000"],
  },

  noConnectionReuse: false,
  insecureSkipTLSVerify: __ENV.TLS_SKIP_VERIFY === "true",
};

// ---------------------------------------------------------------------------
// setup：预创建支付池（用于后续 confirm）
// ---------------------------------------------------------------------------
export function setup() {
  assertCredentials();
  const ids = [];
  const url = `${BASE_URL}${PAYMENTS_ENDPOINT}`;

  for (let i = 0; i < SEED_POOL_SIZE; i++) {
    const body = paymentCreateBody();
    const bodyStr = JSON.stringify(body);
    const headers = buildAuthHeaders("POST", PAYMENTS_ENDPOINT, bodyStr);
    const resp = http.post(url, bodyStr, {
      headers: headers,
      timeout: TIMEOUT_MS,
      tags: { phase: "seed", endpoint: "webhook_seed_create" },
    });
    seedCreateCount.add(1);

    const id = extractPaymentId(resp.body);
    if (id) ids.push(id);
  }

  console.log(`setup: webhook 预创建支付池 ${ids.length} 条`);
  return { baseUrl: BASE_URL, paymentIds: ids };
}

// ---------------------------------------------------------------------------
// 主迭代：confirm（触发 webhook 投递）
// ---------------------------------------------------------------------------
export default function (data) {
  const baseUrl = data.baseUrl;
  const ids = data.paymentIds;

  if (!ids || ids.length === 0) {
    sleep(0.05);
    return;
  }

  const id = ids[Math.floor(Math.random() * ids.length)];
  const path = `${PAYMENTS_ENDPOINT}/${id}/confirm`;
  const body = webhookConfirmBody();
  const bodyStr = JSON.stringify(body);
  const headers = buildAuthHeaders("POST", path, bodyStr);
  const url = `${baseUrl}${path}`;

  group("webhook_confirm", function () {
    const resp = http.post(url, bodyStr, {
      headers: headers,
      timeout: TIMEOUT_MS,
      tags: { endpoint: "webhook_confirm" },
    });

    webhookLatency.add(resp.timings.duration);

    const ok = check(
      resp,
      {
        "status is 200": (r) => r.status === 200,
        "has result": (r) => {
          try {
            const o = JSON.parse(r.body);
            return o && (o.id !== undefined || o.status !== undefined);
          } catch (e) {
            return false;
          }
        },
        "response time < 1s": (r) => r.timings.duration < 1000,
      },
      { endpoint: "webhook_confirm" }
    );

    bizSuccessRate.add(ok && resp.status === 200);

    if (!ok && Math.random() < 0.01) {
      console.warn(
        `webhook_confirm FAIL: id=${id} status=${resp.status} body=${resp.body}`
      );
    }
  });

  // webhook 投递思考时间 100~300ms
  sleep(0.1 + Math.random() * 0.2);
}

export function teardown(data) {
  console.log(
    `webhook-delivery teardown: 池大小=${data.paymentIds.length}`
  );
}
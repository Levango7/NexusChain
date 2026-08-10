/**
 * NexusChain k6 压测 — 支付查询
 * ============================================================================
 * 目标：P99 < 200ms @ 2000 RPS
 * 端点：GET /api/v1/payments/{id}
 *
 * 流程：
 *   1. setup 阶段批量创建 N 条支付，得到 paymentId 池
 *   2. 每个 VU 从池中随机取 id，发起 GET 查询
 *   3. 主指标只统计 GET 阶段（创建阶段单独 group，不计入查询阈值）
 *
 * 运行：
 *   k6 run -e API_KEY=xxx -e SIGNING_SECRET=yyy \
 *          -e BASE_URL_GATEWAY=http://localhost:8080 \
 *          -e SEED_POOL_SIZE=200 \
 *          perf/k6/payment-query.js
 */

import http from "k6/http";
import { check, sleep, group } from "k6";
import { Rate, Trend, Counter } from "k6/metrics";

import { buildAuthHeaders, assertCredentials } from "./utils/auth.js";
import { paymentCreateBody, extractPaymentId } from "./utils/data.js";

// ---------------------------------------------------------------------------
// 配置
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL_GATEWAY || "http://localhost:8080";
const ENDPOINT = "/api/v1/payments";
const TIMEOUT_MS = parseInt(__ENV.TIMEOUT_MS || "10000", 10);
// setup 阶段预创建支付数量（足够大以避免缓存击穿同一条记录）
const SEED_POOL_SIZE = parseInt(__ENV.SEED_POOL_SIZE || "200", 10);

// 自定义指标
const queryLatency = new Trend("payment_query_latency", true);
const bizSuccessRate = new Rate("biz_success_rate");
const seedCreateCount = new Counter("seed_create_count");

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
  tags: { scenario: "payment-query", service: "nexus-gateway" },

  stages: [
    { duration: "30s", target: 500 },
    { duration: "1m", target: 1000 },
    { duration: "2m", target: 2000 },
    { duration: "30s", target: 0 },
  ],

  thresholds: {
    // 仅对 GET 查询生效（用 tag 过滤）
    "http_req_duration{endpoint:payments_query}": ["p(99)<200"],
    "http_req_failed{endpoint:payments_query}": ["rate<0.01"],
    "checks{endpoint:payments_query}": ["rate>0.99"],
    biz_success_rate: ["rate>0.99"],
    payment_query_latency: ["p(99)<200"],
  },

  noConnectionReuse: false,
  insecureSkipTLSVerify: __ENV.TLS_SKIP_VERIFY === "true",
};

// ---------------------------------------------------------------------------
// setup：预创建支付池
// ---------------------------------------------------------------------------
export function setup() {
  assertCredentials();
  const ids = [];
  const url = `${BASE_URL}${ENDPOINT}`;

  for (let i = 0; i < SEED_POOL_SIZE; i++) {
    const body = paymentCreateBody();
    const bodyStr = JSON.stringify(body);
    const headers = buildAuthHeaders("POST", ENDPOINT, bodyStr);
    const resp = http.post(url, bodyStr, {
      headers: headers,
      timeout: TIMEOUT_MS,
      tags: { phase: "seed", endpoint: "payments_seed_create" },
    });
    seedCreateCount.add(1);

    const id = extractPaymentId(resp.body);
    if (id) ids.push(id);
  }

  if (ids.length < 10) {
    console.error(
      `setup: 仅成功创建 ${ids.length}/${SEED_POOL_SIZE} 条支付，查询压测可能无意义。`
    );
  }
  console.log(`setup: 预创建支付池 ${ids.length} 条`);
  return { baseUrl: BASE_URL, paymentIds: ids };
}

// ---------------------------------------------------------------------------
// 主迭代：随机查询
// ---------------------------------------------------------------------------
export default function (data) {
  const baseUrl = data.baseUrl;
  const ids = data.paymentIds;

  if (!ids || ids.length === 0) {
    // 池为空：跳过本轮（避免空指针）
    sleep(0.05);
    return;
  }

  const id = ids[Math.floor(Math.random() * ids.length)];
  const path = `${ENDPOINT}/${id}`;
  const headers = buildAuthHeaders("GET", path, "");
  const url = `${baseUrl}${path}`;

  group("query_payment", function () {
    const resp = http.get(url, {
      headers: headers,
      timeout: TIMEOUT_MS,
      tags: { endpoint: "payments_query" },
    });

    queryLatency.add(resp.timings.duration);

    const ok = check(
      resp,
      {
        "status is 200": (r) => r.status === 200,
        "id matches": (r) => {
          const got = extractPaymentId(r.body);
          return got === id;
        },
        "response time < 200ms": (r) => r.timings.duration < 200,
      },
      { endpoint: "payments_query" }
    );

    bizSuccessRate.add(ok && resp.status === 200);

    if (!ok && Math.random() < 0.01) {
      console.warn(`query_payment FAIL: id=${id} status=${resp.status}`);
    }
  });

  // 查询场景思考时间较短（50~150ms），模拟列表页轮询
  sleep(0.05 + Math.random() * 0.1);
}

export function teardown(data) {
  console.log(
    `payment-query teardown: 池大小=${data.paymentIds.length}`
  );
}
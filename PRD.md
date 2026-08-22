# NexusChain Payment Orchestration - MVP PRD

## 1. Product Summary

NexusChain MVP delivers a **self-hosted, open-source payment orchestration engine** that routes payments across multiple channels (blockchain + traditional PSPs) through a single unified API.

**One-liner**: One API to route payments across chains and PSPs, with full data sovereignty.

## 2. Target User (MVP)

Mid-size e-commerce / SaaS developers (team 5-20, monthly volume $10K-$1M) who need multi-channel payment acceptance without vendor lock-in.

## 3. MVP Feature List

| # | Feature | Priority | Module |
|---|---------|----------|--------|
| F1 | Unified Payment API (`POST /api/v1/payments`) | P0 | gateway |
| F2 | PaymentConnector SPI (pluggable channel adapters) | P0 | gateway |
| F3 | Routing Engine (rule-based: priority/weight/cost) | P0 | gateway |
| F4 | ChainConnector (NEX on-chain settlement via Core RPC) | P0 | gateway+core |
| F5 | MockConnector (sandbox testing) | P0 | gateway |
| F6 | HttpPspConnector (generic REST PSP adapter) | P1 | gateway |
| F7 | Merchant management + API Key auth | P0 | gateway (exists) |
| F8 | Webhook notifications (async, retry) | P0 | gateway (exists) |
| F9 | Payment status query + list | P0 | gateway |
| F10 | Connector health check + auto-failover | P1 | gateway |
| F11 | Explorer: blockchain explorer + payment orchestration dashboard (HMAC auth) | P1 | explorer |
| F12 | Java SDK: PaymentClient | P1 | sdk |
| F13 | Cross-chain Bridge (lock/mint/burn/unlock) | Delivered | bridge |
| F14 | Settlement & Risk Engine | Delivered | settlement |
| F15 | Compliance (KYC/AML/DID) | Delivered | compliance |
| F16 | Analytics (Transaction Graph/BI) | Delivered | analytics |
| F17 | Price Oracle (Aggregated Feed) | Delivered | oracle |
| F18 | Consortium Connector (dual-chain routing) | Delivered | gateway |

## 4. Core API Definition

> **实现说明（API 路径差异）**：本节定义的编排 API `POST /api/v1/payments` 由
> `PaymentOrchestrationController` 提供，作为统一编排入口实际存在并可用。
> 与此同时，生产主链路采用订单模型 API `POST /api/v1/orders`（由
> `nexus-gateway` 的 `PaymentController` 提供，字段语义与编排 API 一致，
> 详见 `PaymentV2Controller` 注释）。两者关系：
> - `/api/v1/orders`：订单生命周期 API（创建→支付→确认→退款），为当前生产主接口；
> - `/api/v1/payments`：编排 API（含 connector/routing-rules 管理），面向编排场景。
> 下文示例沿用 PRD 原始定义的 `/api/v1/payments` 路径，实际对接请以
> `nexus-gateway/README.md` 的 API 概览为准。

### 4.1 Create Payment

```
POST /api/v1/payments
Authorization: Bearer {api_key}
Content-Type: application/json

{
  "amount": 10000,
  "currency": "NEX",
  "description": "Order #12345",
  "metadata": { "order_id": "12345" },
  "routing": {
    "strategy": "priority",
    "preferred_connector": "chain"
  },
  "notify_url": "https://merchant.example.com/webhook"
}

Response 201:
{
  "id": "pay_abc123",
  "status": "PROCESSING",
  "amount": 10000,
  "currency": "NEX",
  "connector": "chain",
  "connector_payment_id": "0x...",
  "created_at": "2026-07-31T12:00:00Z",
  "expires_at": "2026-07-31T12:30:00Z"
}
```

### 4.2 Query Payment

```
GET /api/v1/payments/{id}
Authorization: Bearer {api_key}

Response 200:
{
  "id": "pay_abc123",
  "status": "SUCCEEDED",
  "amount": 10000,
  "currency": "NEX",
  "connector": "chain",
  "connector_payment_id": "0x...",
  "chain_tx_hash": "abcdef...",
  "created_at": "...",
  "confirmed_at": "..."
}
```

### 4.3 List Payments

```
GET /api/v1/payments?status=SUCCEEDED&limit=20&cursor=xxx
Authorization: Bearer {api_key}
```

### 4.4 Connector Management (Admin)

```
GET    /api/v1/connectors              - List registered connectors
POST   /api/v1/connectors              - Register new connector config
GET    /api/v1/connectors/{id}/health  - Health check
DELETE /api/v1/connectors/{id}         - Remove connector
```

### 4.5 Routing Rules (Admin)

```
GET    /api/v1/routing-rules           - List rules
POST   /api/v1/routing-rules           - Create rule
PUT    /api/v1/routing-rules/{id}      - Update rule
DELETE /api/v1/routing-rules/{id}      - Delete rule
```

## 5. Payment Status Lifecycle

```
CREATED → PROCESSING → SUCCEEDED
                    → FAILED
                    → EXPIRED
         → CANCELLED (merchant cancel before processing)
```

## 6. PaymentConnector SPI

```java
public interface PaymentConnector {
    String getId();
    String getType();  // "chain", "http_psp", "mock"
    ConnectorStatus getStatus();
    
    PaymentResult createPayment(PaymentRequest request);
    PaymentStatus queryPayment(String connectorPaymentId);
    RefundResult refund(String connectorPaymentId, long amount);
    HealthCheck healthCheck();
}
```

## 7. Routing Engine

Strategies:
- **priority**: Try connectors in configured order, failover on error
- **weight**: Distribute by percentage (A/B testing)
- **cost**: Route to cheapest connector for given amount/currency
- **explicit**: Merchant specifies connector in request

Rule model:
```json
{
  "id": "rule_1",
  "name": "NEX payments go to chain",
  "conditions": { "currency": "NEX", "amount_gte": 0 },
  "strategy": "priority",
  "connectors": ["chain", "mock"],
  "priority": 10
}
```

## 8. Non-functional Requirements

- Latency: Payment creation < 500ms (excluding chain confirmation)
- Availability: Connector failover < 3s
- Idempotency: Same request_id returns same result
- Audit: All state transitions logged to audit trail
- Security: API Key + HMAC signature (existing)

## 9. Out of Scope (MVP)

- No-code workflow builder
- IoT device payments
- 300+ PSP connectors (only 2-3 to prove architecture)
- MPC multi-sig protocol (GG18/GG20) — in progress
- PoS consensus / L2 Rollup — in progress
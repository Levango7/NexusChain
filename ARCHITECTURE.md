# NexusChain Architecture

## Product Vision

NexusChain is a **Payment Orchestration Platform** built on blockchain infrastructure. It provides multi-chain, multi-channel routing, smart settlement, and subscription management for merchants and enterprises.

Blockchain is the foundational settlement layer — not the product itself. On top of it, we build:
- Unified payment gateway (acquiring)
- Cross-chain asset bridge
- Recurring/subscription billing engine
- IoT device payment protocols (smart city long-term vision)

## Product Philosophy

- Open-source products start less polished than commercial ones — this is normal
- Direction correctness matters more than short-term completeness
- All modules are retained and mature at their own pace; `nexus-java-sdk` / `nexus-js-sdk` are explicitly **Deprecated** (migrated to `nexus-sdk`) and excluded from the build
- Each module matures at its own pace, guided by real usage

## Module Map

| Module | Role | Maturity |
|--------|------|----------|
| nexus-core | Blockchain node (consensus, P2P, storage, RPC) | Core — active |
| nexus-gateway | Merchant payment gateway (orders, checkout, webhooks) | Core — active |
| nexus-bridge | Cross-chain asset bridge (lock/mint/burn/unlock) | Core — active |
| nexus-explorer | Block explorer (React + TypeScript) | Active |
| nexus-sdk | Multi-language SDK (Java, JS) | Active |
| nexus-consortium | Consortium / sidechain — complete PoA chain (consensus, gRPC/WS P2P, 国密 SM2/3/4) | Active — complete chain |
| nexus-exchange-wallet | Exchange/custodial wallet | Active |
| nexus-devtools | Developer tools (CLI, testnet faucet) | Skeleton |
| nexus-rpc-doc | RPC API documentation | Reference |
| nexus-java-sdk | Legacy Java SDK (migrated to nexus-sdk) | Deprecated |
| nexus-js-sdk | Legacy JS SDK (migrated to nexus-sdk) | Deprecated |

## Technology Stack

- **Language**: Java 17
- **Framework**: Spring Boot — root build pins `2.7.18`, `nexus-gateway` uses `3.2.5` (version drift under reconciliation; see audit report B1)
- **Build**: Gradle 7.6+ with toolchain auto-provisioning
- **Database**: PostgreSQL (production), H2 (dev/sandbox)
- **P2P**: gRPC + Protobuf
- **Smart Contracts**: WASM (Wasmer runtime)
- **Frontend**: React + TypeScript + Tailwind CSS
- **Observability**: Micrometer + Prometheus + structured logging
- **Resilience**: Resilience4j (circuit breaker, retry, rate limiter)

## Architecture Layers

```
┌─────────────────────────────────────────────────────┐
│                  Application Layer                    │
│  nexus-explorer │ nexus-devtools │ Merchant Portal   │
├─────────────────────────────────────────────────────┤
│                  Service Layer                        │
│  nexus-gateway │ nexus-bridge │ nexus-exchange-wallet│
├─────────────────────────────────────────────────────┤
│                  SDK / Integration                    │
│  nexus-sdk (Java/JS) │ nexus-rpc-doc                │
├─────────────────────────────────────────────────────┤
│                  Infrastructure Layer                 │
│  nexus-core (consensus, P2P, storage, VM)           │
│  nexus-consortium (governance, permissioning)        │
└─────────────────────────────────────────────────────┘
```

## Payment Orchestration Roadmap

1. **Phase 1** (current): Dual-chain acquiring — Gateway routes to `nexus-core` (settlement mainnet) and `nexus-consortium` (consortium sidechain)
2. **Phase 2**: Multi-channel routing (chain + traditional PSPs)
3. **Phase 3**: Smart split settlement (分账)
4. **Phase 4**: IoT micropayments (device-to-device)
5. **Phase 5**: Full orchestration engine (rule-based routing, fallback, retry)

## Key Design Decisions

- Gateway communicates with Core via HTTP RPC (not embedded)
- Sandbox profile enables zero-dependency local development
- All state transitions are event-driven (Spring ApplicationEvent)
- Idempotency enforced at API layer (request fingerprint)
- Webhook delivery is async with exponential backoff retry
- **Dual-chain settlement (deliberate)**: `nexus-core` is the public settlement mainnet; `nexus-consortium` is a permissioned consortium/sidechain. Both are first-class chains — the product intentionally supports dual-chain, not a single settlement chain. `nexus-consortium` is consolidated into the unified build via Gradle composite build (`includeBuild`).

## Running Locally

```bash
# Prerequisites: JDK 17+
cd nexus-gateway
./gradlew bootRun --args="--spring.profiles.active=sandbox"

# Or use the demo orchestrator:
cd demo
npm install && npm start   # Mock chain on :3000
node run-demo.js           # Full payment flow
```
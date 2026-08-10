# NexusChain Gateway

NexusChain 商户支付网关模块，提供收单、收银台、订阅支付功能。

## 模块定位

NexusChain Gateway 是 NexusChain 支付生态的商户侧入口，负责对接商户系统，处理链上支付的收单逻辑。
商户通过 RESTful API 完成注册、下单、支付确认、退款及订阅授权等操作。

## 核心功能

| 功能 | 说明 |
|------|------|
| 商户注册 | 商户开户、KYC 认证、API 密钥管理 |
| 订单创建 | 创建支付订单，生成收银台跳转链接 |
| 支付确认 | 校验链上交易状态，确认订单完成 |
| 退款 | 基于原订单发起链上退款转账 |
| 订阅授权 | 创建订阅计划，周期性扣款（SUBSCRIPTION_AUTH 交易类型），支持取消 |

## 模块关系

### 与 nexus-exchange-wallet 的关系

Gateway 不直接构造链上交易，而是复用 `nexus-exchange-wallet` 模块的转账构造与签名链路。
Gateway 将业务参数（收款地址、金额、代币类型 NEX）传递给 exchange-wallet，由后者完成
交易组装、私钥签名和广播。

### 与 nexus-sdk 的关系

Gateway 通过 `nexus-sdk` 与区块链节点交互，包括：
- 查询交易确认状态
- 监听链上事件回调
- 读取合约状态（订阅授权记录等）

## API 概览

所有接口均为 RESTful 风格，使用 JSON 交互。

### 商户接口

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/merchants/register` | 商户注册 |
| POST | `/api/v1/merchants/{id}/verify` | 商户认证 |
| POST | `/api/v1/merchants/{id}/api-keys` | 生成 API 密钥 |
| GET  | `/api/v1/merchants/{id}` | 查询商户信息 |

### 支付接口

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/orders` | 创建支付订单 |
| GET  | `/api/v1/orders/{id}` | 查询订单 |
| POST | `/api/v1/orders/{id}/pay` | 发起支付 |
| POST | `/api/v1/orders/{id}/confirm` | 确认支付 |
| POST | `/api/v1/orders/{id}/refund` | 发起退款 |
| GET  | `/api/v1/checkout/{token}` | 收银台页面跳转 |

### 订阅接口

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/subscriptions` | 创建订阅 |
| GET  | `/api/v1/subscriptions/{id}` | 查询订阅 |
| POST | `/api/v1/subscriptions/{id}/charge` | 手动扣款 |
| POST | `/api/v1/subscriptions/{id}/cancel` | 取消订阅 |

### Webhook 接口

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/webhooks/chain-events` | 链上事件回调 |

## 技术栈

- Java 11
- Spring Boot 2.7.x
- Spring Web (RESTful API)
- Spring Data JPA (数据持久化)
- 数据库：MySQL / PostgreSQL

## 构建

```bash
gradle build
```

## 运行

```bash
java -jar build/libs/nexus-gateway.jar
```

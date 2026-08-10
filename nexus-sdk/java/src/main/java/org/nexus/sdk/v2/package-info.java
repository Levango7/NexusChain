/**
 * NexusChain SDK for Java — v2.0.0。
 *
 * <h1>NexusChain SDK v2.0.0 使用文档</h1>
 *
 * <p>NexusChain SDK v2.0.0 是 NexusChain 区块链支付编排平台的官方 Java 客户端，
 * 支持订单管理、支付编排、跨链桥接、订阅管理与多租户管理等完整能力。</p>
 *
 * <h2>目录</h2>
 * <ol>
 *   <li><a href="#quickstart">快速开始</a></li>
 *   <li><a href="#clients">客户端一览</a></li>
 *   <li><a href="#orders">订单与支付</a></li>
 *   <li><a href="#batch">批量支付</a></li>
 *   <li><a href="#solana">Solana 链操作</a></li>
 *   <li><a href="#avalanche">Avalanche C-Chain 操作</a></li>
 *   <li><a href="#crosschain">跨链消息传递</a></li>
 *   <li><a href="#subscription">订阅管理</a></li>
 *   <li><a href="#tenant">多租户管理</a></li>
 *   <li><a href="#errors">错误处理</a></li>
 *   <li><a href="#publish">Maven Central 发布</a></li>
 * </ol>
 *
 * <h2 id="quickstart">1. 快速开始</h2>
 *
 * <h3>Maven 依赖</h3>
 * <pre>{@code
 * <dependency>
 *   <groupId>org.nexus</groupId>
 *   <artifactId>nexus-sdk-java</artifactId>
 *   <version>2.0.0</version>
 * </dependency>
 * }</pre>
 *
 * <h3>Gradle 依赖</h3>
 * <pre>{@code
 * implementation 'org.nexus:nexus-sdk-java:2.0.0'
 * }</pre>
 *
 * <h3>初始化客户端</h3>
 * <pre>{@code
 * NexusChainV2Client client = new NexusChainV2Client(
 *     "https://api.nexuschain.io",      // 网关地址
 *     "your-merchant-api-key"           // 商户 API Key
 * );
 * }</pre>
 *
 * <h2 id="clients">2. 客户端一览</h2>
 *
 * <p>v2.0.0 提供一个统一入口 {@link org.nexus.sdk.v2.NexusChainV2Client}，
 * 通过便捷方法访问各功能子客户端：</p>
 *
 * <table border="1">
 *   <caption>表：v2.0.0 子客户端一览</caption>
 *   <tr><th>便捷方法</th><th>返回类型</th><th>功能</th></tr>
 *   <tr><td>{@code client.solana()}</td><td>{@link org.nexus.sdk.v2.solana.SolanaClient}</td><td>Solana 链操作</td></tr>
 *   <tr><td>{@code client.avalanche()}</td><td>{@link org.nexus.sdk.v2.avalanche.AvalancheClient}</td><td>Avalanche C-Chain 操作</td></tr>
 *   <tr><td>{@code client.crossChain()}</td><td>{@link org.nexus.sdk.v2.crosschain.CrossChainMessageClient}</td><td>跨链消息传递</td></tr>
 *   <tr><td>{@code client.subscriptions()}</td><td>{@link org.nexus.sdk.v2.subscription.SubscriptionClient}</td><td>订阅管理</td></tr>
 *   <tr><td>{@code client.tenants()}</td><td>{@link org.nexus.sdk.v2.tenant.TenantClient}</td><td>多租户管理</td></tr>
 * </table>
 *
 * <h2 id="orders">3. 订单与支付</h2>
 *
 * <pre>{@code
 * // 创建订单
 * Map<String, Object> orderReq = new HashMap<>();
 * orderReq.put("amount", new BigDecimal("100.00"));
 * orderReq.put("tokenSymbol", "USDC");
 * orderReq.put("description", "Test order");
 * JsonNode order = client.createOrder(orderReq);
 * long orderId = order.get("id").asLong();
 *
 * // 发起支付
 * JsonNode payment = client.pay(orderId, "0xPayerAddress");
 *
 * // 游标分页查询订单
 * CursorPage<JsonNode> page = client.listOrders(null, 20, "id,amount,status", null);
 * while (page.hasMore()) {
 *     page = client.listOrders(page.nextCursor(), 20, "id,amount,status", null);
 * }
 * }</pre>
 *
 * <h2 id="batch">4. 批量支付</h2>
 *
 * <pre>{@code
 * List<PaymentItem> items = List.of(
 *     PaymentItem.builder()
 *         .merchantId(1L)
 *         .amount(new BigDecimal("10.00"))
 *         .notifyUrl("https://cb.example.com/pay")
 *         .idempotencyKey("idem-001")
 *         .build(),
 *     PaymentItem.builder()
 *         .merchantId(1L)
 *         .amount(new BigDecimal("20.00"))
 *         .notifyUrl("https://cb.example.com/pay")
 *         .idempotencyKey("idem-002")
 *         .build()
 * );
 * BatchResult result = client.batchCreatePayments(items, "ALL_OR_NOTHING");
 * if (result.allSucceeded()) {
 *     System.out.println("All " + result.succeededCount() + " payments succeeded");
 * } else {
 *     result.failed().forEach(f -> System.err.println(
 *         "Index " + f.index() + " failed: " + f.code() + " - " + f.message()));
 * }
 * }</pre>
 *
 * <h2 id="solana">5. Solana 链操作</h2>
 *
 * <pre>{@code
 * SolanaClient solana = client.solana();
 *
 * // 创建 Solana 支付（1 SOL = 1_000_000_000 lamports）
 * JsonNode payment = solana.createPayment(
 *     "FromBase58PubKey", "ToBase58PubKey", 1_000_000_000L, "native");
 * String signature = payment.get("signature").asText();
 *
 * // 查询交易状态
 * JsonNode status = solana.getTransactionStatus(signature);
 *
 * // 查询余额
 * JsonNode balance = solana.getBalance("FromBase58PubKey");
 *
 * // 估算手续费（priorityLevel: 0=最低, 3=高）
 * JsonNode fee = solana.estimateFee(2);
 * }</pre>
 *
 * <h2 id="avalanche">6. Avalanche C-Chain 操作</h2>
 *
 * <pre>{@code
 * AvalancheClient avalanche = client.avalanche();
 *
 * // 创建 Avalanche 支付
 * JsonNode payment = avalanche.createPayment(
 *     "0xFromAddress", "0xToAddress", new BigDecimal("1.5"), "AVAX");
 * String txHash = payment.get("txHash").asText();
 *
 * // 查询交易状态
 * JsonNode status = avalanche.getTransactionStatus(txHash);
 *
 * // 查询余额
 * JsonNode balance = avalanche.getBalance("0xFromAddress");
 *
 * // 估算 Gas 费
 * JsonNode gas = avalanche.estimateGas(21000L, new BigDecimal("22500000000"));
 * }</pre>
 *
 * <h2 id="crosschain">7. 跨链消息传递</h2>
 *
 * <pre>{@code
 * CrossChainMessageClient crossChain = client.crossChain();
 *
 * // 发送跨链消息
 * JsonNode sent = crossChain.sendMessage(
 *     "ETH", "BSC", "0xRecipient", "hello world", "RAW");
 * String messageId = sent.get("messageId").asText();
 *
 * // 查询消息状态
 * JsonNode status = crossChain.getMessageStatus(messageId);
 *
 * // 查询消息详情
 * JsonNode details = crossChain.getMessageDetails(messageId);
 *
 * // 列出消息（游标分页）
 * CursorPage<JsonNode> page = crossChain.listMessages("ETH", "BSC", null, 20);
 *
 * // 重试失败的消息
 * JsonNode retried = crossChain.retryMessage(messageId);
 * }</pre>
 *
 * <h2 id="subscription">8. 订阅管理</h2>
 *
 * <pre>{@code
 * SubscriptionClient subs = client.subscriptions();
 *
 * // 创建订阅
 * JsonNode sub = subs.createSubscription("plan-pro", "cust-123", "pm-token-abc");
 * String subId = sub.get("subscriptionId").asText();
 *
 * // 查询订阅
 * JsonNode details = subs.getSubscription(subId);
 *
 * // 升级订阅
 * subs.upgradeSubscription(subId, "plan-enterprise");
 *
 * // 降级订阅
 * subs.downgradeSubscription(subId, "plan-starter");
 *
 * // 列出订阅计划
 * CursorPage<JsonNode> plans = subs.listPlans(null, 20);
 *
 * // 查询使用量
 * JsonNode usage = subs.getUsage(subId, "2026-01-01", "2026-02-01");
 *
 * // 取消订阅
 * subs.cancelSubscription(subId, "Customer request");
 * }</pre>
 *
 * <h2 id="tenant">9. 多租户管理</h2>
 *
 * <pre>{@code
 * // 平台管理员视角
 * TenantClient tenants = client.tenants();
 *
 * // 创建租户
 * JsonNode tenant = tenants.createTenant("Acme Corp", "admin@acme.com", "enterprise");
 * String tenantId = tenant.get("tenantId").asText();
 *
 * // 更新租户配置
 * Map<String, Object> updates = new HashMap<>();
 * updates.put("name", "Acme Corporation");
 * updates.put("plan", "enterprise-plus");
 * tenants.updateTenant(tenantId, updates);
 *
 * // 暂停租户
 * tenants.suspendTenant(tenantId, "Non-payment");
 *
 * // 激活租户
 * tenants.reactivateTenant(tenantId);
 *
 * // 查询使用量
 * JsonNode usage = tenants.getUsage(tenantId, "2026-01-01", "2026-02-01");
 *
 * // 查询限流状态
 * JsonNode rateLimit = tenants.getRateLimitStatus(tenantId);
 *
 * // 租户视角（使用租户 API Key）
 * TenantClient tenantClient = new TenantClient(
 *     "https://api.nexuschain.io", null, "tenant-api-key");
 * JsonNode myUsage = tenantClient.getUsage(tenantId, "2026-01-01", "2026-02-01");
 * }</pre>
 *
 * <h2 id="errors">10. 错误处理</h2>
 *
 * <p>所有 API 错误统一抛出 {@link org.nexus.sdk.v2.V2ApiException}，包含：</p>
 * <ul>
 *   <li>{@code httpStatus()} — HTTP 状态码</li>
 *   <li>{@code errorCode()} — 业务错误码（如 "ORDER_NOT_FOUND"）</li>
 *   <li>{@code traceId()} — 链路追踪 ID（用于服务端日志查询）</li>
 *   <li>{@code details()} — 错误详情 Map</li>
 * </ul>
 *
 * <pre>{@code
 * try {
 *     client.getOrder(99999L, null);
 * } catch (V2ApiException e) {
 *     if ("ORDER_NOT_FOUND".equals(e.errorCode())) {
 *         System.err.println("Order not found, traceId=" + e.traceId());
 *     } else if (e.httpStatus() == 429) {
 *         System.err.println("Rate limited, retry later");
 *     } else {
 *         throw e;
 *     }
 * }
 * }</pre>
 *
 * <h2 id="publish">11. Maven Central 发布</h2>
 *
 * <p>SDK 通过 Gradle {@code maven-publish} 插件发布到 Maven Central：</p>
 * <pre>{@code
 * groupId    = org.nexus
 * artifactId = nexus-sdk-java
 * version    = 2.0.0
 * }</pre>
 *
 * <p>发布命令（需在 gradle.properties 中配置 signing 与 sonatype 凭据）：</p>
 * <pre>{@code
 * ./gradlew :nexus-sdk:java:publish
 * }</pre>
 *
 * @since 2.0.0
 * @author NexusChain Team
 */
package org.nexus.sdk.v2;
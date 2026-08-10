import com.fasterxml.jackson.databind.JsonNode;
import org.nexus.sdk.v2.BatchResult;
import org.nexus.sdk.v2.CursorPage;
import org.nexus.sdk.v2.NexusChainV2Client;
import org.nexus.sdk.v2.PaymentItem;
import org.nexus.sdk.v2.V2ApiException;
import org.nexus.sdk.v2.avalanche.AvalancheClient;
import org.nexus.sdk.v2.crosschain.CrossChainMessageClient;
import org.nexus.sdk.v2.solana.SolanaClient;
import org.nexus.sdk.v2.subscription.SubscriptionClient;
import org.nexus.sdk.v2.tenant.TenantClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码示例：v2 API 综合示例（Java）
 *
 * <p>演示 NexusChain SDK v2.0.0 的全部主要功能，包括：</p>
 * <ul>
 *   <li>订单与支付</li>
 *   <li>批量支付</li>
 *   <li>Solana 链操作</li>
 *   <li>Avalanche C-Chain 操作</li>
 *   <li>跨链消息传递</li>
 *   <li>订阅管理</li>
 *   <li>多租户管理</li>
 *   <li>错误处理</li>
 * </ul>
 *
 * <p>本示例仅作演示，实际使用时请根据业务需要选择对应功能。</p>
 */
public class V2ApiExample {

    public static void main(String[] args) {
        NexusChainV2Client client = new NexusChainV2Client(
                "http://localhost:8080",
                "your-merchant-api-key");

        try {
            // ==================== 1. 订单与支付 ====================
            System.out.println("========== 1. 订单与支付 ==========");

            // 创建订单
            Map<String, Object> orderReq = new HashMap<>();
            orderReq.put("amount", new BigDecimal("100.00"));
            orderReq.put("tokenSymbol", "USDC");
            orderReq.put("description", "Comprehensive example order");
            JsonNode order = client.createOrder(orderReq);
            long orderId = order.get("id").asLong();
            System.out.println("创建订单 ID: " + orderId);

            // 发起支付
            JsonNode payment = client.pay(orderId, "0xPayerAddress");
            System.out.println("支付状态: " + payment.get("status").asText());

            // 游标分页查询订单
            CursorPage<JsonNode> page = client.listOrders(null, 20, "id,amount,status", null);
            System.out.println("订单总数（本页）: " + page.count());

            // ==================== 2. 批量支付 ====================
            System.out.println("\n========== 2. 批量支付 ==========");

            List<PaymentItem> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                items.add(PaymentItem.builder()
                        .merchantId(1L)
                        .amount(new BigDecimal("10.00"))
                        .tokenSymbol("USDC")
                        .description("Batch payment #" + i)
                        .notifyUrl("https://cb.example.com/pay")
                        .idempotencyKey("idem-batch-" + i)
                        .build());
            }
            BatchResult batchResult = client.batchCreatePayments(items, "PARTIAL");
            System.out.println("批量支付: 成功 " + batchResult.succeededCount()
                    + " / 失败 " + batchResult.failedCount()
                    + " / 总计 " + batchResult.totalCount());

            // ==================== 3. Solana 链操作 ====================
            System.out.println("\n========== 3. Solana 链操作 ==========");
            SolanaClient solana = client.solana();

            // 估算手续费
            JsonNode solanaFee = solana.estimateFee(2);
            System.out.println("Solana 手续费: " + solanaFee.toPrettyString());

            // 查询余额
            JsonNode solanaBalance = solana.getBalance("DemoSolanaPubKey");
            System.out.println("Solana 余额: " + solanaBalance.toPrettyString());

            // ==================== 4. Avalanche C-Chain 操作 ====================
            System.out.println("\n========== 4. Avalanche C-Chain 操作 ==========");
            AvalancheClient avalanche = client.avalanche();

            // 估算 Gas 费
            JsonNode gas = avalanche.estimateGas(21000L, new BigDecimal("22500000000"));
            System.out.println("Avalanche Gas: " + gas.toPrettyString());

            // 查询余额
            JsonNode avaxBalance = avalanche.getBalance("0xDemoAvalancheAddress");
            System.out.println("Avalanche 余额: " + avaxBalance.toPrettyString());

            // ==================== 5. 跨链消息传递 ====================
            System.out.println("\n========== 5. 跨链消息传递 ==========");
            CrossChainMessageClient crossChain = client.crossChain();

            // 发送跨链消息
            JsonNode msg = crossChain.sendMessage(
                    "ETH", "BSC", "0xRecipient", "SGVsbG8gV29ybGQ=", "RAW");
            System.out.println("跨链消息 ID: " + msg.get("messageId").asText());

            // 列出消息
            CursorPage<JsonNode> msgs = crossChain.listMessages("ETH", "BSC", null, 10);
            System.out.println("ETH→BSC 消息数（本页）: " + msgs.count());

            // ==================== 6. 订阅管理 ====================
            System.out.println("\n========== 6. 订阅管理 ==========");
            SubscriptionClient subs = client.subscriptions();

            // 列出订阅计划
            CursorPage<JsonNode> plans = subs.listPlans(null, 20);
            System.out.println("可用订阅计划数: " + plans.count());
            for (JsonNode plan : plans.data()) {
                System.out.println("  - " + plan.get("planId").asText()
                        + ": " + plan.get("name").asText());
            }

            // ==================== 7. 多租户管理 ====================
            System.out.println("\n========== 7. 多租户管理 ==========");
            TenantClient tenants = client.tenants();

            // 创建租户
            JsonNode tenant = tenants.createTenant(
                    "Demo Tenant", "demo@tenant.com", "pro");
            System.out.println("创建租户 ID: " + tenant.get("tenantId").asText());

            // 查询限流状态
            String tenantId = tenant.get("tenantId").asText();
            JsonNode rateLimit = tenants.getRateLimitStatus(tenantId);
            System.out.println("租户限流状态: " + rateLimit.toPrettyString());

        } catch (V2ApiException e) {
            // ==================== 8. 错误处理 ====================
            System.err.println("\n========== API 错误 ==========");
            System.err.println("HTTP 状态: " + e.httpStatus());
            System.err.println("错误码: " + e.errorCode());
            System.err.println("错误消息: " + e.getMessage());
            System.err.println("追踪 ID: " + e.traceId());
            if (e.details() != null) {
                System.err.println("详情: " + e.details());
            }
        } catch (Exception e) {
            System.err.println("非 API 错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
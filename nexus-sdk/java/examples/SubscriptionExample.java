import com.fasterxml.jackson.databind.JsonNode;
import org.nexus.sdk.v2.CursorPage;
import org.nexus.sdk.v2.NexusChainV2Client;
import org.nexus.sdk.v2.subscription.SubscriptionClient;

/**
 * 代码示例：订阅管理示例（Java）
 *
 * <p>演示如何使用 NexusChain SDK v2.0.0 管理订阅：创建、查询、升级、降级、
 * 取消、列出计划与查询使用量。</p>
 */
public class SubscriptionExample {

    public static void main(String[] args) {
        NexusChainV2Client client = new NexusChainV2Client(
                "http://localhost:8080",
                "your-merchant-api-key");

        SubscriptionClient subs = client.subscriptions();

        try {
            // 1. 列出可用的订阅计划
            System.out.println("=== 列出订阅计划 ===");
            CursorPage<JsonNode> plans = subs.listPlans(null, 20);
            for (JsonNode plan : plans.data()) {
                System.out.println("  - " + plan.get("planId").asText()
                        + ": " + plan.get("name").asText()
                        + " ($" + plan.get("priceMonthly").asText() + "/月)");
            }

            // 2. 创建订阅
            System.out.println("\n=== 创建订阅 ===");
            JsonNode created = subs.createSubscription(
                    "plan-pro",              // 计划 ID
                    "cust-123",              // 客户 ID
                    "pm_token_abc123"        // 支付方式 token（由支付网关颁发）
            );
            System.out.println("订阅创建结果: " + created.toPrettyString());

            String subscriptionId = created.get("subscriptionId").asText();
            System.out.println("订阅 ID: " + subscriptionId);

            // 3. 查询订阅详情
            System.out.println("\n=== 查询订阅 ===");
            JsonNode details = subs.getSubscription(subscriptionId);
            System.out.println("订阅详情: " + details.toPrettyString());

            // 4. 查询当前周期使用量
            System.out.println("\n=== 查询使用量 ===");
            JsonNode usage = subs.getUsage(subscriptionId, "2026-01-01", "2026-02-01");
            System.out.println("使用量: " + usage.toPrettyString());

            // 5. 升级订阅到 enterprise 计划
            System.out.println("\n=== 升级订阅 ===");
            JsonNode upgraded = subs.upgradeSubscription(subscriptionId, "plan-enterprise");
            System.out.println("升级结果: " + upgraded.toPrettyString());
            if (upgraded.has("prorationAmount")) {
                System.out.println("按比例补差: $" + upgraded.get("prorationAmount").asText());
            }

            // 6. 降级订阅回 pro 计划
            System.out.println("\n=== 降级订阅 ===");
            JsonNode downgraded = subs.downgradeSubscription(subscriptionId, "plan-pro");
            System.out.println("降级结果: " + downgraded.toPrettyString());

            // 7. 取消订阅
            System.out.println("\n=== 取消订阅 ===");
            JsonNode canceled = subs.cancelSubscription(subscriptionId, "Customer request");
            System.out.println("取消结果: " + canceled.toPrettyString());

        } catch (Exception e) {
            System.err.println("失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
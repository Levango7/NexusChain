import com.fasterxml.jackson.databind.JsonNode;
import org.nexus.sdk.v2.NexusChainV2Client;
import org.nexus.sdk.v2.tenant.TenantClient;

import java.util.HashMap;
import java.util.Map;

/**
 * 代码示例：多租户管理示例（Java）
 *
 * <p>演示如何使用 NexusChain SDK v2.0.0 管理多租户：创建、查询、更新、
 * 暂停、激活、使用量查询与限流状态查询。</p>
 *
 * <p>多租户管理涉及两种身份认证：</p>
 * <ul>
 *   <li><b>商户 API Key</b>（{@code X-NexusChain-ApiKey}）：平台管理员视角</li>
 *   <li><b>租户 API Key</b>（{@code X-Tenant-Api-Key}）：租户视角</li>
 * </ul>
 */
public class MultiTenantExample {

    public static void main(String[] args) {
        // === 平台管理员视角 ===
        NexusChainV2Client client = new NexusChainV2Client(
                "http://localhost:8080",
                "platform-admin-api-key");

        TenantClient tenants = client.tenants();

        try {
            // 1. 创建租户
            System.out.println("=== 创建租户 ===");
            JsonNode created = tenants.createTenant(
                    "Acme Corp",          // 租户名称
                    "admin@acme.com",     // 管理员邮箱
                    "enterprise"          // 订阅计划
            );
            System.out.println("租户创建结果: " + created.toPrettyString());

            String tenantId = created.get("tenantId").asText();
            String tenantApiKey = created.get("apiKey").asText();
            System.out.println("租户 ID: " + tenantId);
            System.out.println("租户 API Key: " + tenantApiKey);

            // 2. 查询租户
            System.out.println("\n=== 查询租户 ===");
            JsonNode details = tenants.getTenant(tenantId);
            System.out.println("租户详情: " + details.toPrettyString());

            // 3. 更新租户配置
            System.out.println("\n=== 更新租户配置 ===");
            Map<String, Object> updates = new HashMap<>();
            updates.put("name", "Acme Corporation");
            updates.put("plan", "enterprise-plus");
            Map<String, Object> settings = new HashMap<>();
            settings.put("maxApiRate", 10000);
            settings.put("maxStorageGb", 500);
            updates.put("settings", settings);

            JsonNode updated = tenants.updateTenant(tenantId, updates);
            System.out.println("更新结果: " + updated.toPrettyString());

            // 4. 查询使用量
            System.out.println("\n=== 查询使用量 ===");
            JsonNode usage = tenants.getUsage(tenantId, "2026-01-01", "2026-02-01");
            System.out.println("使用量: " + usage.toPrettyString());

            // 5. 查询限流状态
            System.out.println("\n=== 查询限流状态 ===");
            JsonNode rateLimit = tenants.getRateLimitStatus(tenantId);
            System.out.println("限流状态: " + rateLimit.toPrettyString());

            // 6. 暂停租户（如未付费）
            System.out.println("\n=== 暂停租户 ===");
            JsonNode suspended = tenants.suspendTenant(tenantId, "Non-payment");
            System.out.println("暂停结果: " + suspended.toPrettyString());

            // 7. 激活租户
            System.out.println("\n=== 激活租户 ===");
            JsonNode reactivated = tenants.reactivateTenant(tenantId);
            System.out.println("激活结果: " + reactivated.toPrettyString());

            // === 租户视角 ===
            System.out.println("\n=== 切换到租户视角 ===");
            TenantClient tenantClient = new TenantClient(
                    "http://localhost:8080",
                    null,                // 不使用商户 API Key
                    tenantApiKey         // 使用租户 API Key
            );

            // 租户查询自己的信息
            JsonNode myInfo = tenantClient.getTenant(tenantId);
            System.out.println("租户自身信息: " + myInfo.toPrettyString());

            // 租户查询自己的使用量
            JsonNode myUsage = tenantClient.getUsage(tenantId, "2026-01-01", "2026-02-01");
            System.out.println("租户自身使用量: " + myUsage.toPrettyString());

        } catch (Exception e) {
            System.err.println("失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
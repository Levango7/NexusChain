import com.fasterxml.jackson.databind.JsonNode;
import org.nexus.sdk.v2.NexusChainV2Client;
import org.nexus.sdk.v2.avalanche.AvalancheClient;

import java.math.BigDecimal;

/**
 * 代码示例：Avalanche 支付示例（Java）
 *
 * <p>演示如何使用 NexusChain SDK v2.0.0 创建 Avalanche C-Chain 支付、
 * 查询交易状态、查询余额与估算 Gas 费。</p>
 *
 * <p>运行前请确保：</p>
 * <ol>
 *   <li>NexusChain 网关已启动并监听 http://localhost:8080</li>
 *   <li>已注册商户并获得 API Key</li>
 *   <li>Avalanche bridge 已配置并连接到 Avalanche C-Chain（fuji/mainnet）</li>
 * </ol>
 */
public class AvalanchePaymentExample {

    public static void main(String[] args) {
        // 1. 初始化客户端
        NexusChainV2Client client = new NexusChainV2Client(
                "http://localhost:8080",
                "your-merchant-api-key");

        // 2. 获取 Avalanche 子客户端
        AvalancheClient avalanche = client.avalanche();

        // 示例地址（请替换为真实地址）
        String fromAddress = "0xFromAddress0123456789abcdef0123456789abcdef";
        String toAddress = "0xToAddress0123456789abcdef0123456789abcdef01";

        try {
            // 3. 查询付款方余额
            System.out.println("=== 查询余额 ===");
            JsonNode balance = avalanche.getBalance(fromAddress);
            System.out.println("余额: " + balance.toPrettyString());

            // 4. 估算 Gas 费
            //    典型 AVAX 转账：gasUnits=21000, gasPrice=22500000000 (22.5 nAVAX)
            System.out.println("\n=== 估算 Gas 费 ===");
            JsonNode gas = avalanche.estimateGas(21000L, new BigDecimal("22500000000"));
            System.out.println("Gas 估算: " + gas.toPrettyString());

            // 5. 创建 Avalanche 支付（1.5 AVAX）
            //    assetId="AVAX" 表示原生 AVAX；若支付 ARC-20 token，传入合约地址
            System.out.println("\n=== 创建支付 ===");
            JsonNode payment = avalanche.createPayment(
                    fromAddress, toAddress, new BigDecimal("1.5"), "AVAX");
            System.out.println("支付结果: " + payment.toPrettyString());

            String txHash = payment.get("txHash").asText();
            System.out.println("交易哈希: " + txHash);

            // 6. 轮询交易状态
            System.out.println("\n=== 查询交易状态 ===");
            for (int i = 0; i < 10; i++) {
                JsonNode status = avalanche.getTransactionStatus(txHash);
                String statusStr = status.get("status").asText();
                System.out.println("第 " + (i + 1) + " 次查询: " + statusStr);

                if ("CONFIRMED".equals(statusStr) || "SUCCESS".equals(statusStr)) {
                    System.out.println("交易已确认！");
                    System.out.println("详情: " + status.toPrettyString());
                    break;
                } else if ("FAILED".equals(statusStr)) {
                    System.out.println("交易失败！");
                    System.out.println("详情: " + status.toPrettyString());
                    break;
                }

                Thread.sleep(3000);  // Avalanche 出块约 2 秒
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("中断: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
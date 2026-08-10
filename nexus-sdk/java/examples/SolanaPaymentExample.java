import com.fasterxml.jackson.databind.JsonNode;
import org.nexus.sdk.v2.NexusChainV2Client;
import org.nexus.sdk.v2.solana.SolanaClient;

/**
 * 代码示例：Solana 支付示例（Java）
 *
 * <p>演示如何使用 NexusChain SDK v2.0.0 创建 Solana 支付、查询交易状态、
 * 查询余额与估算手续费。</p>
 *
 * <p>运行前请确保：</p>
 * <ol>
 *   <li>NexusChain 网关已启动并监听 http://localhost:8080</li>
 *   <li>已注册商户并获得 API Key</li>
 *   <li>Solana bridge 已配置并连接到 Solana 集群（devnet/mainnet）</li>
 * </ol>
 */
public class SolanaPaymentExample {

    public static void main(String[] args) {
        // 1. 初始化客户端
        NexusChainV2Client client = new NexusChainV2Client(
                "http://localhost:8080",
                "your-merchant-api-key");

        // 2. 获取 Solana 子客户端
        SolanaClient solana = client.solana();

        // 示例地址（请替换为真实地址）
        String fromAddress = "FromBase58PubKeyXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
        String toAddress = "ToBase58PubKeyYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY";

        try {
            // 3. 查询付款方余额
            System.out.println("=== 查询余额 ===");
            JsonNode balance = solana.getBalance(fromAddress);
            System.out.println("余额: " + balance.toPrettyString());

            // 4. 估算手续费（priorityLevel=2，中等优先级）
            System.out.println("\n=== 估算手续费 ===");
            JsonNode fee = solana.estimateFee(2);
            System.out.println("手续费: " + fee.toPrettyString());

            // 5. 创建 Solana 支付（0.001 SOL = 1_000_000 lamports）
            //    mint="native" 表示原生 SOL；若支付 SPL Token，传入 token mint 地址
            System.out.println("\n=== 创建支付 ===");
            JsonNode payment = solana.createPayment(
                    fromAddress, toAddress, 1_000_000L, "native");
            System.out.println("支付结果: " + payment.toPrettyString());

            String signature = payment.get("signature").asText();
            System.out.println("交易签名: " + signature);

            // 6. 轮询交易状态（实际生产中应使用 webhook 通知）
            System.out.println("\n=== 查询交易状态 ===");
            for (int i = 0; i < 10; i++) {
                JsonNode status = solana.getTransactionStatus(signature);
                String statusStr = status.get("status").asText();
                System.out.println("第 " + (i + 1) + " 次查询: " + statusStr);

                if ("CONFIRMED".equals(statusStr) || "FINALIZED".equals(statusStr)) {
                    System.out.println("交易已确认！");
                    System.out.println("详情: " + status.toPrettyString());
                    break;
                } else if ("FAILED".equals(statusStr)) {
                    System.out.println("交易失败！");
                    System.out.println("详情: " + status.toPrettyString());
                    break;
                }

                Thread.sleep(2000);  // 等待 2 秒后重试
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
import com.fasterxml.jackson.databind.JsonNode;
import org.nexus.sdk.v2.CursorPage;
import org.nexus.sdk.v2.NexusChainV2Client;
import org.nexus.sdk.v2.crosschain.CrossChainMessageClient;

/**
 * 代码示例：跨链消息示例（Java）
 *
 * <p>演示如何使用 NexusChain SDK v2.0.0 发送跨链消息、查询消息状态、
 * 列出消息与重试失败的消息。</p>
 *
 * <p>支持的链标识：ETH、BSC、POLYGON、SOLANA、AVALANCHE 等。</p>
 *
 * <p>支持的消息格式：</p>
 * <ul>
 *   <li>{@code "RAW"} — 原始字节（base64 编码）</li>
 *   <li>{@code "PROTOBUF"} — Protocol Buffers 编码</li>
 *   <li>{@code "JSON"} — JSON 字符串</li>
 * </ul>
 */
public class CrossChainMessageExample {

    public static void main(String[] args) {
        NexusChainV2Client client = new NexusChainV2Client(
                "http://localhost:8080",
                "your-merchant-api-key");

        CrossChainMessageClient crossChain = client.crossChain();

        try {
            // 1. 发送跨链消息（ETH → BSC）
            System.out.println("=== 发送跨链消息 ===");
            JsonNode sent = crossChain.sendMessage(
                    "ETH",           // 源链
                    "BSC",           // 目标链
                    "0xRecipientOnBsc",  // 接收方地址
                    "SGVsbG8gQ3Jvc3MgQ2hhaW4h",  // base64("Hello Cross Chain!")
                    "RAW"            // 消息格式
            );
            System.out.println("发送结果: " + sent.toPrettyString());

            String messageId = sent.get("messageId").asText();
            System.out.println("消息 ID: " + messageId);

            // 2. 查询消息状态
            System.out.println("\n=== 查询消息状态 ===");
            JsonNode status = crossChain.getMessageStatus(messageId);
            System.out.println("状态: " + status.toPrettyString());

            // 3. 查询消息详情
            System.out.println("\n=== 查询消息详情 ===");
            JsonNode details = crossChain.getMessageDetails(messageId);
            System.out.println("详情: " + details.toPrettyString());

            // 4. 列出消息（游标分页）
            System.out.println("\n=== 列出消息（ETH → BSC）===");
            String cursor = null;
            int total = 0;
            do {
                CursorPage<JsonNode> page = crossChain.listMessages(
                        "ETH", "BSC", cursor, 10);
                System.out.println("本页 " + page.data().size() + " 条，hasMore=" + page.hasMore());

                for (JsonNode msg : page.data()) {
                    total++;
                    System.out.println("  - id=" + msg.get("messageId").asText()
                            + ", status=" + msg.get("status").asText());
                }

                cursor = page.nextCursor();
                if (!page.hasMore()) break;
            } while (cursor != null && total < 50);  // 最多列 50 条

            System.out.println("总计列出 " + total + " 条消息");

            // 5. 重试失败的消息（演示用）
            // 假设有一个失败的消息 ID
            String failedMessageId = "msg-failed-001";
            System.out.println("\n=== 重试失败消息 ===");
            try {
                JsonNode retried = crossChain.retryMessage(failedMessageId);
                System.out.println("重试结果: " + retried.toPrettyString());
            } catch (Exception e) {
                System.out.println("重试失败（消息可能不存在或非失败状态）: " + e.getMessage());
            }

            // 6. 发送 JSON 格式消息
            System.out.println("\n=== 发送 JSON 格式消息 ===");
            String jsonPayload = "{\"action\":\"transfer\",\"amount\":\"100\",\"token\":\"USDC\"}";
            JsonNode jsonMsg = crossChain.sendMessage(
                    "POLYGON", "AVALANCHE",
                    "0xRecipientOnAvalanche",
                    jsonPayload,
                    "JSON"
            );
            System.out.println("JSON 消息发送结果: " + jsonMsg.toPrettyString());

        } catch (Exception e) {
            System.err.println("失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
package org.nexus.core.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nexus.keystore.util.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量转账 payload 解析与构建工具类。
 *
 * <p>用于 {@code BATCH_TRANSFER} 交易类型中 payload 字段的序列化与反序列化。
 * payload 格式为 JSON 对象，包含 {@code total_count}（总笔数）、
 * {@code total_amount}（总金额）和 {@code items}（转账项数组）三个字段。
 * 每个 item 包含 {@code address}（收款地址）和 {@code amount}（转账金额）。</p>
 *
 * <p>JSON 格式示例：
 * <pre>
 * {
 *   "total_count": 2,
 *   "total_amount": 3000,
 *   "items": [
 *     {"address":"NEX1abc...", "amount":1000},
 *     {"address":"NEX2def...", "amount":2000}
 *   ]
 * }
 * </pre></p>
 *
 * <p>同时也兼容纯 JSON 数组格式的 payload，便于向后兼容。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class BatchTransferPayload {

    /** payload JSON 中总笔数字段名。 */
    public static final String FIELD_TOTAL_COUNT = "total_count";

    /** payload JSON 中总金额字段名。 */
    public static final String FIELD_TOTAL_AMOUNT = "total_amount";

    /** payload JSON 中转账项列表字段名。 */
    public static final String FIELD_ITEMS = "items";

    /**
     * 单笔转账项，包含收款地址和转账金额。
     */
    public static class TransferItem {

        /** 收款人地址（NEX 地址格式）。 */
        private String address;

        /** 转账金额（单位：NEX 最小单位）。 */
        private long amount;

        /**
         * 默认构造函数，供 JSON 反序列化使用。
         */
        public TransferItem() {
        }

        /**
         * 全参数构造函数。
         *
         * @param address 收款人地址
         * @param amount  转账金额
         */
        public TransferItem(String address, long amount) {
            this.address = address;
            this.amount = amount;
        }

        /**
         * 获取收款人地址。
         * @return 收款人地址
         */
        public String getAddress() {
            return address;
        }

        /**
         * 设置收款人地址。
         * @param address 收款人地址
         */
        public void setAddress(String address) {
            this.address = address;
        }

        /**
         * 获取转账金额。
         * @return 转账金额
         */
        public long getAmount() {
            return amount;
        }

        /**
         * 设置转账金额。
         * @param amount 转账金额
         */
        public void setAmount(long amount) {
            this.amount = amount;
        }

        @Override
        public String toString() {
            return "TransferItem{address='" + address + "', amount=" + amount + "}";
        }
    }

    /**
     * 将 payload 字节数组解析为转账项列表。
     *
     * <p>payload 内容应为 UTF-8 编码的 JSON 字符串。支持两种格式：
     * <ul>
     *   <li>JSON 对象格式（包含 total_count、total_amount、items 字段）</li>
     *   <li>纯 JSON 数组格式（直接为 TransferItem 数组，向后兼容）</li>
     * </ul></p>
     *
     * <p>使用 {@link JSON#parseArray(String, Class)} 进行列表解析。</p>
     *
     * @param payload 交易 payload 字节数组
     * @return 转账项列表，如果 payload 为 null 或空则返回空列表
     */
    public static List<TransferItem> parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new ArrayList<>();
        }
        String json = new String(payload, StandardCharsets.UTF_8);
        // 尝试解析为 JSON 对象（包含 total_count、total_amount、items）
        JsonNode parsed = JsonUtils.readTree(json);
        if (parsed.isObject()) {
            JsonNode itemsNode = parsed.get(FIELD_ITEMS);
            if (itemsNode != null && itemsNode.isArray()) {
                return JsonUtils.MAPPER.convertValue(itemsNode,
                        JsonUtils.MAPPER.getTypeFactory().constructCollectionType(List.class, TransferItem.class));
            }
        }
        // 兼容纯 JSON 数组格式
        return JsonUtils.fromJsonList(json, TransferItem.class);
    }

    /**
     * 将转账项列表构建为 payload 字节数组。
     *
     * <p>输出为 UTF-8 编码的 JSON 字符串。JSON 对象包含以下字段：
     * <ul>
     *   <li>{@code total_count} - 转账总笔数</li>
     *   <li>{@code total_amount} - 转账总金额</li>
     *   <li>{@code items} - 转账项数组</li>
     * </ul></p>
     *
     * <p>items 数组通过 {@link JSON#toJSONString(Object)} 序列化。</p>
     *
     * @param items 转账项列表
     * @return payload 字节数组，如果 items 为 null 或空则返回空字节数组
     */
    public static byte[] build(List<TransferItem> items) {
        if (items == null || items.isEmpty()) {
            return new byte[0];
        }
        ObjectNode payload = JsonUtils.MAPPER.createObjectNode();
        payload.put(FIELD_TOTAL_COUNT, items.size());
        payload.put(FIELD_TOTAL_AMOUNT, getTotalAmount(items));
        payload.set(FIELD_ITEMS, JsonUtils.MAPPER.valueToTree(items));
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 验证转账项列表的合法性。
     *
     * <p>验证规则：
     * <ul>
     *   <li>items 不为 null 且不为空</li>
     *   <li>items 数量 <= maxRecipients</li>
     *   <li>总金额 <= maxTotalAmount</li>
     *   <li>每笔转账金额 > 0</li>
     * </ul></p>
     *
     * @param items          转账项列表
     * @param maxRecipients  最大收款人数量
     * @param maxTotalAmount 最大总金额
     * @return true 如果验证通过
     * @throws IllegalArgumentException 如果任何验证规则不满足
     */
    public static boolean validate(List<TransferItem> items, int maxRecipients, long maxTotalAmount) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Transfer items must not be null or empty");
        }
        if (items.size() > maxRecipients) {
            throw new IllegalArgumentException(
                    "Number of recipients exceeds maximum: " + items.size() + " > " + maxRecipients);
        }
        long total = getTotalAmount(items);
        if (total > maxTotalAmount) {
            throw new IllegalArgumentException(
                    "Total amount exceeds maximum: " + total + " > " + maxTotalAmount);
        }
        for (int i = 0; i < items.size(); i++) {
            TransferItem item = items.get(i);
            if (item == null) {
                throw new IllegalArgumentException("Transfer item at index " + i + " is null");
            }
            if (item.getAmount() <= 0) {
                throw new IllegalArgumentException(
                        "Transfer amount at index " + i + " must be positive, got: " + item.getAmount());
            }
            if (item.getAddress() == null || item.getAddress().trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Transfer address at index " + i + " must not be null or empty");
            }
        }
        return true;
    }

    /**
     * 计算转账项列表的总金额。
     *
     * @param items 转账项列表
     * @return 总金额，如果 items 为 null 或空则返回 0
     */
    public static long getTotalAmount(List<TransferItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (TransferItem item : items) {
            if (item != null) {
                total += item.getAmount();
            }
        }
        return total;
    }
}

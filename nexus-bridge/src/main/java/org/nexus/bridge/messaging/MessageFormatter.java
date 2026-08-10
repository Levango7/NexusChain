package org.nexus.bridge.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 跨链消息格式化器。
 *
 * <p>负责将业务参数组装为 {@link CrossChainMessage}，并提供消息的编码 / 解码能力：</p>
 * <ul>
 *   <li>{@link #formatMessage} — 组装消息并自动生成 messageId</li>
 *   <li>{@link #encodeForSigning} — 将消息编码为确定性字节串（用于签名 / 哈希）</li>
 *   <li>{@link #computeMessageId} — 计算消息 ID（基于编码后字节的 SHA-256）</li>
 *   <li>{@link #encode} — 将消息序列化为传输字符串（JSON 风格，便于跨进程传输）</li>
 *   <li>{@link #decodeMessage} — 反序列化为 {@link CrossChainMessage}</li>
 * </ul>
 *
 * <h2>编码格式</h2>
 * <p>签名编码采用管道分隔的规范化字符串（类似 EIP-712 结构化数据 hash）：</p>
 * <pre>
 *   sourceChain | targetChain | sourceContract | targetContract
 *     | payloadType | payloadEncodedData | nonce | timestamp
 * </pre>
 * 再以 UTF-8 字节通过 SHA-256 计算摘要，得到 messageId（0x + 64 hex）。
 *
 * <p>传输编码采用紧凑 JSON 风格字符串，字段顺序固定，便于解码。</p>
 *
 * @since 1.9.2
 */
public class MessageFormatter {

    /** 字段分隔符（用于签名编码）。 */
    private static final String FIELD_SEP = "|";

    /** JSON 字段分隔符（用于传输编码）。 */
    private static final String JSON_SEP = ",";

    /** Hex 格式化器。 */
    private static final HexFormat HEX = HexFormat.of();

    /**
     * 将业务参数格式化为跨链消息。
     *
     * <p>自动计算 payload 编码与 messageId，并设置当前时间戳（若 {@code timestamp <= 0}）。</p>
     *
     * @param sourceChain    源链 ID
     * @param targetChain    目标链 ID
     * @param sourceContract 源合约地址
     * @param targetContract 目标合约地址
     * @param payload        消息负载
     * @param nonce          消息序号
     * @param timestamp      创建时间戳（epoch 秒，<=0 表示使用当前时间）
     * @return 组装完成的跨链消息
     */
    public CrossChainMessage formatMessage(String sourceChain, String targetChain,
                                           String sourceContract, String targetContract,
                                           MessagePayload payload,
                                           long nonce, long timestamp) {
        validateNonEmpty("sourceChain", sourceChain);
        validateNonEmpty("targetChain", targetChain);
        validateNonEmpty("sourceContract", sourceContract);
        validateNonEmpty("targetContract", targetContract);
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }

        payload.encode();
        long ts = timestamp > 0 ? timestamp : System.currentTimeMillis() / 1000;

        CrossChainMessage message = new CrossChainMessage();
        message.setSourceChain(sourceChain);
        message.setTargetChain(targetChain);
        message.setSourceContract(sourceContract);
        message.setTargetContract(targetContract);
        message.setPayload(payload);
        message.setNonce(nonce);
        message.setTimestamp(ts);
        message.setStatus(MessageStatus.PENDING);

        String messageId = computeMessageId(message);
        message.setMessageId(messageId);
        return message;
    }

    /**
     * 将消息编码为用于签名的字节串。
     *
     * <p>采用规范化管道分隔字符串，UTF-8 编码为字节。
     * 同一消息（相同字段值）始终产生相同字节串，保证签名确定性。</p>
     *
     * @param message 跨链消息
     * @return 用于签名的字节
     */
    public byte[] encodeForSigning(CrossChainMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Message must not be null");
        }
        if (message.getPayload() == null) {
            throw new IllegalArgumentException("Message payload must not be null");
        }
        String normalized = buildSigningString(message);
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 将消息编码为用于签名的 hex 字符串。
     *
     * @param message 跨链消息
     * @return hex 字符串
     */
    public String encodeForSigningHex(CrossChainMessage message) {
        return HEX.formatHex(encodeForSigning(message));
    }

    /**
     * 计算消息 ID（基于签名编码的 SHA-256 摘要）。
     *
     * @param message 跨链消息
     * @return 0x + 64 hex 字符的消息 ID
     */
    public String computeMessageId(CrossChainMessage message) {
        byte[] signingBytes = encodeForSigning(message);
        return "0x" + HEX.formatHex(sha256(signingBytes));
    }

    /**
     * 将消息序列化为传输字符串（紧凑 JSON 风格）。
     *
     * <p>字段顺序固定：messageId, sourceChain, targetChain, sourceContract,
     * targetContract, payloadType, payloadData, nonce, timestamp, status。</p>
     *
     * @param message 跨链消息
     * @return 编码字符串
     */
    public String encode(CrossChainMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Message must not be null");
        }
        MessagePayload p = message.getPayload();
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"messageId\":\"").append(nullToEmpty(message.getMessageId())).append('"').append(JSON_SEP);
        sb.append("\"sourceChain\":\"").append(nullToEmpty(message.getSourceChain())).append('"').append(JSON_SEP);
        sb.append("\"targetChain\":\"").append(nullToEmpty(message.getTargetChain())).append('"').append(JSON_SEP);
        sb.append("\"sourceContract\":\"").append(nullToEmpty(message.getSourceContract())).append('"').append(JSON_SEP);
        sb.append("\"targetContract\":\"").append(nullToEmpty(message.getTargetContract())).append('"').append(JSON_SEP);
        sb.append("\"payloadType\":\"").append(p == null ? "" : p.getType().name()).append('"').append(JSON_SEP);
        sb.append("\"payloadData\":\"").append(p == null ? "" : escape(p.getData())).append('"').append(JSON_SEP);
        sb.append("\"nonce\":").append(message.getNonce()).append(JSON_SEP);
        sb.append("\"timestamp\":").append(message.getTimestamp()).append(JSON_SEP);
        sb.append("\"status\":\"").append(message.getStatus() == null ? "" : message.getStatus().name()).append('"');
        sb.append('}');
        return sb.toString();
    }

    /**
     * 将编码字符串解码为跨链消息。
     *
     * <p>解析 {@link #encode} 产生的紧凑 JSON 风格字符串。
     * 签名列表不在传输编码中（签名由中继器单独附加）。</p>
     *
     * @param encoded 编码字符串
     * @return 解码后的跨链消息
     * @throws IllegalArgumentException 如果编码格式非法
     */
    public CrossChainMessage decodeMessage(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Encoded string must not be null or empty");
        }
        String s = encoded.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) {
            throw new IllegalArgumentException("Invalid message encoding: must be wrapped in {}");
        }
        // 去除外层花括号
        s = s.substring(1, s.length() - 1);

        String messageId = extractJsonString(s, "messageId");
        String sourceChain = extractJsonString(s, "sourceChain");
        String targetChain = extractJsonString(s, "targetChain");
        String sourceContract = extractJsonString(s, "sourceContract");
        String targetContract = extractJsonString(s, "targetContract");
        String payloadTypeStr = extractJsonString(s, "payloadType");
        String payloadData = extractJsonString(s, "payloadData");
        long nonce = extractJsonLong(s, "nonce");
        long timestamp = extractJsonLong(s, "timestamp");
        String statusStr = extractJsonString(s, "status");

        MessagePayload.Type pType;
        try {
            pType = payloadTypeStr.isEmpty() ? MessagePayload.Type.ARBITRARY
                    : MessagePayload.Type.valueOf(payloadTypeStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown payload type: " + payloadTypeStr);
        }
        MessagePayload payload = new MessagePayload(pType, unescape(payloadData)).encode();

        CrossChainMessage message = new CrossChainMessage();
        message.setMessageId(messageId);
        message.setSourceChain(sourceChain);
        message.setTargetChain(targetChain);
        message.setSourceContract(sourceContract);
        message.setTargetContract(targetContract);
        message.setPayload(payload);
        message.setNonce(nonce);
        message.setTimestamp(timestamp);
        try {
            message.setStatus(statusStr.isEmpty() ? MessageStatus.PENDING
                    : MessageStatus.valueOf(statusStr));
        } catch (IllegalArgumentException e) {
            message.setStatus(MessageStatus.PENDING);
        }
        return message;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 构造签名规范化字符串。
     *
     * <p>字段顺序固定，避免不同实现因字段顺序差异导致签名不一致。</p>
     */
    private String buildSigningString(CrossChainMessage m) {
        MessagePayload p = m.getPayload();
        return nullToEmpty(m.getSourceChain()) + FIELD_SEP
                + nullToEmpty(m.getTargetChain()) + FIELD_SEP
                + nullToEmpty(m.getSourceContract()) + FIELD_SEP
                + nullToEmpty(m.getTargetContract()) + FIELD_SEP
                + (p == null ? "" : p.getType().name()) + FIELD_SEP
                + (p == null ? "" : p.getEncodedData()) + FIELD_SEP
                + m.getNonce() + FIELD_SEP
                + m.getTimestamp();
    }

    /** 计算 SHA-256 摘要。 */
    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /** 校验字符串非空。 */
    private static void validateNonEmpty(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty");
        }
    }

    /** null 安全转空串。 */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** JSON 字符串转义（仅处理 " 与 \）。 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** JSON 字符串反转义。 */
    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * 从 JSON 字符串中提取指定字段的字符串值。
     *
     * <p>简单解析，假设字段值不含转义后的 {@code ","} 分隔符冲突。</p>
     */
    private static String extractJsonString(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = start;
        boolean escaped = false;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (escaped) {
                escaped = false;
                end++;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                end++;
                continue;
            }
            if (c == '"') break;
            end++;
        }
        return json.substring(start, end);
    }

    /**
     * 从 JSON 字符串中提取指定字段的长整数值。
     */
    private static long extractJsonLong(String json, String field) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start < 0) return 0L;
        start += key.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        try {
            return Long.parseLong(json.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
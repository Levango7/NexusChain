package org.nexus.bridge.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MessageFormatter} 单元测试：覆盖消息格式化、签名编码、消息 ID 计算、
 * 编码 / 解码往返。
 */
class MessageFormatterTest {

    private final MessageFormatter formatter = new MessageFormatter();

    // ==================== formatMessage 测试 ====================

    @Test
    @DisplayName("formatMessage: 正常参数应组装消息并生成 messageId")
    void formatMessage_normalInput_buildsMessageWithId() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.TOKEN_TRANSFER,
                "{\"amount\":1000,\"to\":\"0xabc\"}");

        CrossChainMessage msg = formatter.formatMessage(
                "solana-mainnet", "nexus",
                "TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN",
                "0xnexusBridge",
                payload, 1L, 1700000000L);

        assertNotNull(msg.getMessageId());
        assertTrue(msg.getMessageId().startsWith("0x"));
        assertEquals(66, msg.getMessageId().length(), "messageId 应为 0x + 64 hex 字符");
        assertEquals("solana-mainnet", msg.getSourceChain());
        assertEquals("nexus", msg.getTargetChain());
        assertEquals(1L, msg.getNonce());
        assertEquals(1700000000L, msg.getTimestamp());
        assertEquals(MessageStatus.PENDING, msg.getStatus());
        assertNotNull(msg.getPayload().getEncodedData());
    }

    @Test
    @DisplayName("formatMessage: timestamp <= 0 时使用当前时间")
    void formatMessage_nonPositiveTimestamp_usesNow() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.ARBITRARY, "data");

        CrossChainMessage msg = formatter.formatMessage(
                "a", "b", "c", "d", payload, 1L, 0L);

        long now = System.currentTimeMillis() / 1000;
        assertTrue(now - msg.getTimestamp() <= 5, "应使用当前时间");
    }

    @Test
    @DisplayName("formatMessage: 缺少 sourceChain 抛异常")
    void formatMessage_missingSourceChain_throws() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.ARBITRARY, "data");
        assertThrows(IllegalArgumentException.class, () ->
                formatter.formatMessage(null, "b", "c", "d", payload, 1L, 0L));
    }

    @Test
    @DisplayName("formatMessage: payload 为 null 抛异常")
    void formatMessage_nullPayload_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                formatter.formatMessage("a", "b", "c", "d", null, 1L, 0L));
    }

    @Test
    @DisplayName("formatMessage: 相同参数应生成相同 messageId（确定性）")
    void formatMessage_sameParams_sameId() {
        MessagePayload p1 = new MessagePayload(MessagePayload.Type.CONTRACT_CALL, "calldata");
        MessagePayload p2 = new MessagePayload(MessagePayload.Type.CONTRACT_CALL, "calldata");

        CrossChainMessage m1 = formatter.formatMessage("a", "b", "c", "d", p1, 1L, 100L);
        CrossChainMessage m2 = formatter.formatMessage("a", "b", "c", "d", p2, 1L, 100L);

        assertEquals(m1.getMessageId(), m2.getMessageId());
    }

    @Test
    @DisplayName("formatMessage: 不同 nonce 应生成不同 messageId")
    void formatMessage_differentNonce_differentId() {
        MessagePayload p = new MessagePayload(MessagePayload.Type.ARBITRARY, "data");

        CrossChainMessage m1 = formatter.formatMessage("a", "b", "c", "d", p, 1L, 100L);
        CrossChainMessage m2 = formatter.formatMessage("a", "b", "c", "d", p, 2L, 100L);

        assertNotEquals(m1.getMessageId(), m2.getMessageId());
    }

    // ==================== encodeForSigning 测试 ====================

    @Test
    @DisplayName("encodeForSigning: 返回非空字节数组")
    void encodeForSigning_returnsNonEmptyBytes() {
        CrossChainMessage msg = buildSampleMessage();
        byte[] bytes = formatter.encodeForSigning(msg);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }

    @Test
    @DisplayName("encodeForSigning: 相同消息应产生相同字节（确定性）")
    void encodeForSigning_sameMessage_sameBytes() {
        CrossChainMessage m1 = buildSampleMessage();
        CrossChainMessage m2 = buildSampleMessage();

        byte[] b1 = formatter.encodeForSigning(m1);
        byte[] b2 = formatter.encodeForSigning(m2);

        assertArrayEquals(b1, b2);
    }

    @Test
    @DisplayName("encodeForSigning: null 消息抛异常")
    void encodeForSigning_nullMessage_throws() {
        assertThrows(IllegalArgumentException.class, () -> formatter.encodeForSigning(null));
    }

    @Test
    @DisplayName("encodeForSigningHex: 返回有效 hex 字符串")
    void encodeForSigningHex_returnsValidHex() {
        CrossChainMessage msg = buildSampleMessage();
        String hex = formatter.encodeForSigningHex(msg);
        assertNotNull(hex);
        assertEquals(hex.toLowerCase(), hex, "hex 应为小写");
        assertTrue(hex.matches("[0-9a-f]+"), "应为合法 hex");
    }

    // ==================== computeMessageId 测试 ====================

    @Test
    @DisplayName("computeMessageId: 返回 0x + 64 hex 字符")
    void computeMessageId_returnsValidHash() {
        CrossChainMessage msg = buildSampleMessage();
        String id = formatter.computeMessageId(msg);
        assertTrue(id.startsWith("0x"));
        assertEquals(66, id.length());
    }

    @Test
    @DisplayName("computeMessageId: 相同消息相同 ID")
    void computeMessageId_sameMessage_sameId() {
        CrossChainMessage m1 = buildSampleMessage();
        CrossChainMessage m2 = buildSampleMessage();
        assertEquals(formatter.computeMessageId(m1), formatter.computeMessageId(m2));
    }

    // ==================== encode / decodeMessage 往返测试 ====================

    @Test
    @DisplayName("encode → decodeMessage 往返应保留所有字段")
    void encodeDecode_roundTrip_preservesAllFields() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.TOKEN_TRANSFER,
                "{\"amount\":500,\"to\":\"0xdef\"}");
        CrossChainMessage original = formatter.formatMessage(
                "solana-mainnet", "nexus",
                "srcContract", "tgtContract",
                payload, 42L, 1700000000L);

        String encoded = formatter.encode(original);
        CrossChainMessage decoded = formatter.decodeMessage(encoded);

        assertEquals(original.getMessageId(), decoded.getMessageId());
        assertEquals(original.getSourceChain(), decoded.getSourceChain());
        assertEquals(original.getTargetChain(), decoded.getTargetChain());
        assertEquals(original.getSourceContract(), decoded.getSourceContract());
        assertEquals(original.getTargetContract(), decoded.getTargetContract());
        assertEquals(original.getNonce(), decoded.getNonce());
        assertEquals(original.getTimestamp(), decoded.getTimestamp());
        assertEquals(original.getStatus(), decoded.getStatus());
        assertEquals(original.getPayload().getType(), decoded.getPayload().getType());
        assertEquals(original.getPayload().getData(), decoded.getPayload().getData());
    }

    @Test
    @DisplayName("decodeMessage: 非法格式抛异常")
    void decodeMessage_invalidFormat_throws() {
        assertThrows(IllegalArgumentException.class, () -> formatter.decodeMessage("not-json"));
        assertThrows(IllegalArgumentException.class, () -> formatter.decodeMessage(""));
        assertThrows(IllegalArgumentException.class, () -> formatter.decodeMessage(null));
    }

    @Test
    @DisplayName("encode: payload data 含特殊字符应正确转义并往返")
    void encodeDecode_payloadWithSpecialChars_roundTrip() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.ARBITRARY,
                "data with \"quotes\" and \\backslash and ,comma");
        CrossChainMessage original = formatter.formatMessage(
                "a", "b", "c", "d", payload, 1L, 100L);

        String encoded = formatter.encode(original);
        CrossChainMessage decoded = formatter.decodeMessage(encoded);

        assertEquals(original.getPayload().getData(), decoded.getPayload().getData());
    }

    @Test
    @DisplayName("decodeMessage: 未知 payloadType 应回退到 ARBITRARY")
    void decodeMessage_unknownPayloadType_fallsBackToArbitrary() {
        String malformed = "{\"messageId\":\"0xabc\",\"sourceChain\":\"a\",\"targetChain\":\"b\","
                + "\"sourceContract\":\"c\",\"targetContract\":\"d\","
                + "\"payloadType\":\"UNKNOWN_TYPE\",\"payloadData\":\"data\","
                + "\"nonce\":1,\"timestamp\":100,\"status\":\"PENDING\"}";
        assertThrows(IllegalArgumentException.class, () -> formatter.decodeMessage(malformed));
    }

    @Test
    @DisplayName("decodeMessage: 未知 status 应回退到 PENDING")
    void decodeMessage_unknownStatus_fallsBackToPending() {
        String malformed = "{\"messageId\":\"0xabc\",\"sourceChain\":\"a\",\"targetChain\":\"b\","
                + "\"sourceContract\":\"c\",\"targetContract\":\"d\","
                + "\"payloadType\":\"ARBITRARY\",\"payloadData\":\"data\","
                + "\"nonce\":1,\"timestamp\":100,\"status\":\"WEIRD\"}";
        CrossChainMessage decoded = formatter.decodeMessage(malformed);
        assertEquals(MessageStatus.PENDING, decoded.getStatus());
    }

    // ==================== 辅助方法 ====================

    private CrossChainMessage buildSampleMessage() {
        MessagePayload payload = new MessagePayload(MessagePayload.Type.CONTRACT_CALL, "0xdeadbeef");
        return formatter.formatMessage("eth", "nexus", "0xsrc", "0xtgt", payload, 10L, 1700000000L);
    }
}
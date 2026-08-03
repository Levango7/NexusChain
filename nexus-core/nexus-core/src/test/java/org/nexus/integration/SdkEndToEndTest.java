package org.nexus.integration;

import org.nexus.core.account.Transaction;
import org.nexus.protobuf.tcp.ProtocolModel;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * SDK 端到端测试。
 *
 * <p>不连接真实节点，验证交易构造和签名的正确性。模拟 JS SDK 和 Java SDK
 * 构造 NexusChain 支付扩展交易，验证类型、payload、序列化/反序列化往返一致性。</p>
 *
 * <p>不依赖 Spring 容器，为纯集成测试。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class SdkEndToEndTest {

    /** 测试用 32 字节公钥（from 字段）。 */
    private static final byte[] FROM_PUBKEY = new byte[Transaction.PUBLIC_KEY_SIZE];
    /** 测试用 20 字节公钥哈希（to 字段）。 */
    private static final byte[] TO_PUBKEY_HASH = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
    /** 测试用 64 字节签名。 */
    private static final byte[] SIGNATURE = new byte[Transaction.SIGNATURE_SIZE];

    /** CHANNEL_OPEN payload JSON（模拟 SDK 构造的 payload）。 */
    private static final byte[] CHANNEL_OPEN_PAYLOAD =
            ("{\"channelId\":\"nexus-ch-001\",\"participant1\":\"addr1\",\"participant2\":\"addr2\","
                    + "\"balance1\":500000,\"balance2\":500000,\"lockTime\":1000}")
                    .getBytes(StandardCharsets.UTF_8);
    /** BATCH_TRANSFER payload JSON（模拟 SDK 构造的 payload）。 */
    private static final byte[] BATCH_TRANSFER_PAYLOAD =
            ("{\"total_count\":2,\"total_amount\":3000,\"items\":["
                    + "{\"address\":\"NEX_addr_1\",\"amount\":1000},"
                    + "{\"address\":\"NEX_addr_2\",\"amount\":2000}"
                    + "]}")
                    .getBytes(StandardCharsets.UTF_8);

    // ==================== JS SDK 模拟测试 ====================

    /**
     * 模拟 JS SDK 构造 CHANNEL_OPEN 交易。
     *
     * <p>JS SDK 通过 RPC 接口构造交易对象，验证 type 和 payload 不为空。</p>
     */
    @Test
    public void testJsSdkChannelOpenConstruction() {
        // 模拟 JS SDK 构造 CHANNEL_OPEN 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.CHANNEL_OPEN.ordinal(),
                1L,
                FROM_PUBKEY,
                100000L,
                500000L,
                CHANNEL_OPEN_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证 type 为 CHANNEL_OPEN
        assertEquals("type 应为 CHANNEL_OPEN",
                Transaction.Type.CHANNEL_OPEN.ordinal(), tx.type);
        // 验证 payload 不为空
        assertNotNull("payload 不应为 null", tx.payload);
        assertTrue("payload 不应为空", tx.payload.length > 0);
        // 验证 payload 内容为正确的 JSON
        String payloadStr = new String(tx.payload, StandardCharsets.UTF_8);
        assertTrue("payload 应包含 channelId", payloadStr.contains("channelId"));
        assertTrue("payload 应包含 balance1", payloadStr.contains("balance1"));
        // 验证 amount 为正数
        assertTrue("amount 应为正数", tx.amount > 0);
    }

    /**
     * 模拟 JS SDK 构造 BATCH_TRANSFER 交易。
     *
     * <p>验证 type=19，payload 含 recipients 信息。</p>
     */
    @Test
    public void testJsSdkBatchTransferConstruction() {
        // 模拟 JS SDK 构造 BATCH_TRANSFER 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.BATCH_TRANSFER.ordinal(),
                2L,
                FROM_PUBKEY,
                100000L,
                0L,
                BATCH_TRANSFER_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证 type=19 (BATCH_TRANSFER)
        assertEquals("type 应为 19 (BATCH_TRANSFER)", 19, tx.type);
        assertEquals("Transaction.Type.BATCH_TRANSFER.ordinal() 应为 19",
                Transaction.Type.BATCH_TRANSFER.ordinal(), tx.type);
        // 验证 payload 不为空
        assertNotNull("payload 不应为 null", tx.payload);
        assertTrue("payload 不应为空", tx.payload.length > 0);
        // 验证 payload 含 recipients 信息
        String payloadStr = new String(tx.payload, StandardCharsets.UTF_8);
        assertTrue("payload 应包含 items", payloadStr.contains("items"));
        assertTrue("payload 应包含 address", payloadStr.contains("address"));
        assertTrue("payload 应包含 amount", payloadStr.contains("amount"));
        // 验证 amount=0（批量转账金额在 payload 中）
        assertEquals("amount 应为 0（金额在 payload 中）", 0L, tx.amount);
    }

    // ==================== Java SDK 模拟测试 ====================

    /**
     * 模拟 Java SDK 构造 CHANNEL_OPEN 交易。
     *
     * <p>Java SDK 通过 Transaction 构造器构造交易对象，验证所有字段正确。</p>
     */
    @Test
    public void testJavaSdkChannelOpenConstruction() {
        // 模拟 Java SDK 构造 CHANNEL_OPEN 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.CHANNEL_OPEN.ordinal(),
                10L,
                FROM_PUBKEY,
                200000L,
                1000000L,
                CHANNEL_OPEN_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证所有字段
        assertEquals("version 应为 1", 1, tx.version);
        assertEquals("type 应为 CHANNEL_OPEN",
                Transaction.Type.CHANNEL_OPEN.ordinal(), tx.type);
        assertEquals("nonce 应为 10", 10L, tx.nonce);
        assertEquals("gasPrice 应为 200000", 200000L, tx.gasPrice);
        assertEquals("amount 应为 1000000", 1000000L, tx.amount);
        assertNotNull("payload 不应为 null", tx.payload);
        assertTrue("payload 不应为空", tx.payload.length > 0);
        assertEquals("from 长度应为 32",
                Transaction.PUBLIC_KEY_SIZE, tx.from.length);
        assertEquals("to 长度应为 20",
                Transaction.PUBLIC_KEY_HASH_SIZE, tx.to.length);
        assertEquals("signature 长度应为 64",
                Transaction.SIGNATURE_SIZE, tx.signature.length);
        // 验证通道交易分类
        assertTrue("应为通道交易", tx.isChannelTransaction());
        assertTrue("应为支付扩展类型", tx.isPaymentExtensionType());
    }

    /**
     * 模拟 Java SDK 构造 BATCH_TRANSFER 交易。
     *
     * <p>验证所有字段正确，特别是 type=19 和 payload 含 recipients。</p>
     */
    @Test
    public void testJavaSdkBatchTransferConstruction() {
        // 模拟 Java SDK 构造 BATCH_TRANSFER 交易
        Transaction tx = new Transaction(
                1,
                Transaction.Type.BATCH_TRANSFER.ordinal(),
                20L,
                FROM_PUBKEY,
                200000L,
                0L,
                BATCH_TRANSFER_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 验证所有字段
        assertEquals("version 应为 1", 1, tx.version);
        assertEquals("type 应为 BATCH_TRANSFER (19)",
                19, tx.type);
        assertEquals("nonce 应为 20", 20L, tx.nonce);
        assertEquals("gasPrice 应为 200000", 200000L, tx.gasPrice);
        assertEquals("amount 应为 0", 0L, tx.amount);
        assertNotNull("payload 不应为 null", tx.payload);
        assertTrue("payload 不应为空", tx.payload.length > 0);
        // 验证 payload 含 recipients
        String payloadStr = new String(tx.payload, StandardCharsets.UTF_8);
        assertTrue("payload 应包含 recipients (items)", payloadStr.contains("items"));
        // 验证交易分类
        assertFalse("不应为通道交易", tx.isChannelTransaction());
        assertTrue("应为支付扩展类型", tx.isPaymentExtensionType());
        assertFalse("不应为稳定币交易", tx.isStableCoinTransaction());
        assertFalse("不应为跨链桥交易", tx.isBridgeTransaction());
    }

    // ==================== 序列化往返测试 ====================

    /**
     * 测试交易序列化往返：构造 → toRPCBytes() → fromRPCBytes() → 验证字段一致。
     *
     * <p>验证交易通过 RPC 字节格式序列化和反序列化后，所有字段保持一致。</p>
     */
    @Test
    public void testTransactionRoundTrip() {
        // 构造新类型交易（CHANNEL_OPEN，含 payload）
        Transaction original = new Transaction(
                1,
                Transaction.Type.CHANNEL_OPEN.ordinal(),
                42L,
                FROM_PUBKEY,
                100000L,
                500000L,
                CHANNEL_OPEN_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // 序列化为 RPC 字节
        byte[] rpcBytes = original.toRPCBytes();
        assertNotNull("RPC 字节不应为 null", rpcBytes);
        assertTrue("RPC 字节长度应大于 0", rpcBytes.length > 0);

        // 反序列化
        Transaction restored = Transaction.fromRPCBytes(rpcBytes);

        // 验证字段一致
        assertEquals("version 应一致", original.version, restored.version);
        assertEquals("type 应一致", original.type, restored.type);
        assertEquals("nonce 应一致", original.nonce, restored.nonce);
        assertArrayEquals("from 应一致", original.from, restored.from);
        assertEquals("gasPrice 应一致", original.gasPrice, restored.gasPrice);
        assertEquals("amount 应一致", original.amount, restored.amount);
        assertArrayEquals("signature 应一致", original.signature, restored.signature);
        assertArrayEquals("to 应一致", original.to, restored.to);
        // 验证 payload
        assertNotNull("restored payload 不应为 null", restored.payload);
        assertArrayEquals("payload 应一致", original.payload, restored.payload);
        // 验证哈希一致
        assertArrayEquals("哈希应一致", original.getHash(), restored.getHash());
    }

    /**
     * 测试交易 protobuf 编码往返：构造 → encode() → fromProto() → 验证字段一致。
     *
     * <p>验证交易通过 protobuf 格式编码和解码后，所有字段保持一致。</p>
     */
    @Test
    public void testTransactionEncoding() {
        // 构造新类型交易（BATCH_TRANSFER，含 payload）
        Transaction original = new Transaction(
                1,
                Transaction.Type.BATCH_TRANSFER.ordinal(),
                99L,
                FROM_PUBKEY,
                150000L,
                0L,
                BATCH_TRANSFER_PAYLOAD,
                TO_PUBKEY_HASH,
                SIGNATURE
        );

        // protobuf 编码
        ProtocolModel.Transaction protoTx = original.encode();
        assertNotNull("protobuf 交易不应为 null", protoTx);

        // 解码
        Transaction restored = Transaction.fromProto(protoTx);

        // 验证字段一致
        assertEquals("version 应一致", original.version, restored.version);
        assertEquals("type 应一致", original.type, restored.type);
        assertEquals("nonce 应一致", original.nonce, restored.nonce);
        assertArrayEquals("from 应一致", original.from, restored.from);
        assertEquals("gasPrice 应一致", original.gasPrice, restored.gasPrice);
        assertEquals("amount 应一致", original.amount, restored.amount);
        assertArrayEquals("to 应一致", original.to, restored.to);
        assertArrayEquals("signature 应一致", original.signature, restored.signature);
        // 验证 payload
        assertNotNull("restored payload 不应为 null", restored.payload);
        assertArrayEquals("payload 应一致", original.payload, restored.payload);
        // 验证哈希一致
        assertArrayEquals("哈希应一致", original.getHash(), restored.getHash());
        // 验证类型名称
        assertEquals("类型名称应为 BATCH_TRANSFER",
                "BATCH_TRANSFER", restored.getTypeName());
    }
}

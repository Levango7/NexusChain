package org.nexus.payment;

import org.nexus.core.payment.ChannelUpdate;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.crypto.ed25519.Ed25519PublicKey;
import org.nexus.crypto.HashUtil;
import org.nexus.encoding.BigEndian;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 链下状态更新消息测试。
 *
 * <p>验证 ChannelUpdate 的创建、签名消息布局、签名验证流程、
 * 余额守恒检查、双方签名状态判断以及 JSON 序列化往返。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class ChannelUpdateTest {

    /** 测试用通道 ID。 */
    private static final String CHANNEL_ID = "nexus-channel-001";
    /** 测试用总余额（NEX 最小单位）。 */
    private static final long TOTAL_BALANCE = 1000L;
    /** 签名长度常量。 */
    private static final int SIGNATURE_SIZE = 64;

    // ==================== 辅助方法 ====================

    /**
     * 生成一对 Ed25519 密钥用于测试签名。
     */
    private Ed25519KeyPair generateTestKeyPair() {
        return Ed25519.generateKeyPair();
    }

    /**
     * 用私钥对 ChannelUpdate 的消息哈希签名。
     */
    private byte[] signUpdate(ChannelUpdate update, byte[] privateKeyBytes) {
        Ed25519PrivateKey privateKey = new Ed25519PrivateKey(privateKeyBytes);
        byte[] messageHash = HashUtil.keccak256(update.getMessageToSign());
        return privateKey.sign(messageHash);
    }

    // ==================== 测试方法 ====================

    /**
     * 测试创建 ChannelUpdate 并验证基本字段。
     */
    @Test
    public void testCreateUpdate() {
        // 创建一个链下状态更新消息
        long nonce = 1L;
        long balance1 = 900L;
        long balance2 = 100L;
        byte[] sig1 = new byte[SIGNATURE_SIZE];
        byte[] sig2 = new byte[SIGNATURE_SIZE];
        long timestamp = System.currentTimeMillis();

        ChannelUpdate update = new ChannelUpdate(
                CHANNEL_ID, nonce, balance1, balance2,
                sig1, sig2, timestamp
        );

        // 验证所有字段正确设置
        assertEquals("channelId 应与构造参数一致", CHANNEL_ID, update.getChannelId());
        assertEquals("nonce 应与构造参数一致", nonce, update.getNonce());
        assertEquals("balance1 应与构造参数一致", balance1, update.getBalance1());
        assertEquals("balance2 应与构造参数一致", balance2, update.getBalance2());
        assertEquals("timestamp 应与构造参数一致", timestamp, update.getTimestamp());
        assertNotNull("signature1 不应为 null", update.getSignature1());
        assertNotNull("signature2 不应为 null", update.getSignature2());
    }

    /**
     * 测试签名消息的字节布局：channelId(UTF-8) + nonce(8字节大端) + balance1(8字节大端) + balance2(8字节大端)。
     */
    @Test
    public void testGetMessageToSign() {
        long nonce = 5L;
        long balance1 = 700L;
        long balance2 = 300L;

        ChannelUpdate update = new ChannelUpdate(
                CHANNEL_ID, nonce, balance1, balance2,
                null, null, System.currentTimeMillis()
        );

        byte[] message = update.getMessageToSign();
        assertNotNull("签名消息不应为 null", message);

        // 验证消息长度 = channelId字节 + 8 + 8 + 8
        byte[] channelIdBytes = CHANNEL_ID.getBytes(StandardCharsets.UTF_8);
        int expectedLength = channelIdBytes.length + 8 + 8 + 8;
        assertEquals("消息长度应为 channelId长度 + 24", expectedLength, message.length);

        // 验证 channelId 部分
        byte[] messageChannelId = Arrays.copyOfRange(message, 0, channelIdBytes.length);
        assertArrayEquals("消息前缀应为 channelId 的 UTF-8 字节", channelIdBytes, messageChannelId);

        // 验证 nonce 部分（8字节大端编码）
        byte[] messageNonce = Arrays.copyOfRange(message, channelIdBytes.length, channelIdBytes.length + 8);
        byte[] expectedNonce = BigEndian.encodeUint64(nonce);
        assertArrayEquals("nonce 应为 8 字节大端编码", expectedNonce, messageNonce);

        // 验证 balance1 部分
        int offset1 = channelIdBytes.length + 8;
        byte[] messageBalance1 = Arrays.copyOfRange(message, offset1, offset1 + 8);
        byte[] expectedBalance1 = BigEndian.encodeUint64(balance1);
        assertArrayEquals("balance1 应为 8 字节大端编码", expectedBalance1, messageBalance1);

        // 验证 balance2 部分
        int offset2 = channelIdBytes.length + 16;
        byte[] messageBalance2 = Arrays.copyOfRange(message, offset2, offset2 + 8);
        byte[] expectedBalance2 = BigEndian.encodeUint64(balance2);
        assertArrayEquals("balance2 应为 8 字节大端编码", expectedBalance2, messageBalance2);
    }

    /**
     * 测试签名验证流程（使用真实密钥对）。
     */
    @Test
    public void testVerifySignatures() {
        // 生成两对 Ed25519 密钥
        Ed25519KeyPair keyPair1 = generateTestKeyPair();
        Ed25519KeyPair keyPair2 = generateTestKeyPair();

        byte[] pubkey1 = keyPair1.getPublicKey().getEncoded();
        byte[] pubkey2 = keyPair2.getPublicKey().getEncoded();
        byte[] prikey1 = keyPair1.getPrivateKey().getEncoded();
        byte[] prikey2 = keyPair2.getPrivateKey().getEncoded();

        // 创建未签名更新
        ChannelUpdate update = new ChannelUpdate(
                CHANNEL_ID, 1L, 800L, 200L,
                null, null, System.currentTimeMillis()
        );

        // 双方签名
        byte[] sig1 = signUpdate(update, prikey1);
        byte[] sig2 = signUpdate(update, prikey2);
        update.setSignature1(sig1);
        update.setSignature2(sig2);

        // 验证签名有效
        assertTrue("双方签名验证应通过", update.verifySignatures(pubkey1, pubkey2));

        // 验证错误公钥下签名验证失败
        byte[] wrongPubkey = new byte[32];
        assertFalse("错误公钥应使签名验证失败", update.verifySignatures(wrongPubkey, pubkey2));
    }

    /**
     * 测试余额守恒检查：总额不变且非负。
     */
    @Test
    public void testIsBalanceConserved() {
        // 余额守恒且非负
        ChannelUpdate validUpdate = new ChannelUpdate(
                CHANNEL_ID, 1L, 600L, 400L,
                null, null, System.currentTimeMillis()
        );
        assertTrue("余额守恒应返回 true", validUpdate.isBalanceConserved(TOTAL_BALANCE));

        // 余额不守恒（总额变化）
        ChannelUpdate unbalancedUpdate = new ChannelUpdate(
                CHANNEL_ID, 1L, 600L, 500L,
                null, null, System.currentTimeMillis()
        );
        assertFalse("余额不守恒应返回 false", unbalancedUpdate.isBalanceConserved(TOTAL_BALANCE));

        // 负余额
        ChannelUpdate negativeUpdate = new ChannelUpdate(
                CHANNEL_ID, 1L, -100L, 1100L,
                null, null, System.currentTimeMillis()
        );
        assertFalse("负余额应返回 false", negativeUpdate.isBalanceConserved(TOTAL_BALANCE));

        // 另一个负余额
        ChannelUpdate negativeUpdate2 = new ChannelUpdate(
                CHANNEL_ID, 1L, 1100L, -100L,
                null, null, System.currentTimeMillis()
        );
        assertFalse("负余额应返回 false", negativeUpdate2.isBalanceConserved(TOTAL_BALANCE));

        // 总额为零的边界情况
        ChannelUpdate zeroUpdate = new ChannelUpdate(
                CHANNEL_ID, 1L, 0L, 0L,
                null, null, System.currentTimeMillis()
        );
        assertFalse("总额为零不等于通道总额应返回 false", zeroUpdate.isBalanceConserved(TOTAL_BALANCE));
    }

    /**
     * 测试单方签名 vs 双方签名状态。
     */
    @Test
    public void testIsFullySigned() {
        byte[] validSig = new byte[SIGNATURE_SIZE]; // 64 字节全零占位

        // 双方都未签名
        ChannelUpdate unsigned = new ChannelUpdate(
                CHANNEL_ID, 1L, 800L, 200L,
                null, null, System.currentTimeMillis()
        );
        assertFalse("无签名不应视为已完全签名", unsigned.isFullySigned());
        assertFalse("signature1 不存在", unsigned.hasSignature1());
        assertFalse("signature2 不存在", unsigned.hasSignature2());

        // 仅一方签名
        ChannelUpdate halfSigned = new ChannelUpdate(
                CHANNEL_ID, 1L, 800L, 200L,
                validSig, null, System.currentTimeMillis()
        );
        assertFalse("单方签名不应视为已完全签名", halfSigned.isFullySigned());
        assertTrue("signature1 应存在", halfSigned.hasSignature1());
        assertFalse("signature2 不应存在", halfSigned.hasSignature2());

        // 双方都签名
        ChannelUpdate fullySigned = new ChannelUpdate(
                CHANNEL_ID, 1L, 800L, 200L,
                validSig, validSig, System.currentTimeMillis()
        );
        assertTrue("双方签名应视为已完全签名", fullySigned.isFullySigned());
        assertTrue("signature1 应存在", fullySigned.hasSignature1());
        assertTrue("signature2 应存在", fullySigned.hasSignature2());

        // 签名长度不正确
        ChannelUpdate wrongSize = new ChannelUpdate(
                CHANNEL_ID, 1L, 800L, 200L,
                new byte[32], new byte[64], System.currentTimeMillis()
        );
        assertFalse("错误长度的签名不应视为已完全签名", wrongSize.isFullySigned());
        assertFalse("长度不对的 signature1 不应存在", wrongSize.hasSignature1());
        assertTrue("长度正确的 signature2 应存在", wrongSize.hasSignature2());
    }

    /**
     * 测试 JSON 序列化往返。
     */
    @Test
    public void testToJsonFromJson() {
        // 创建带签名的 ChannelUpdate
        byte[] sig1 = new byte[SIGNATURE_SIZE];
        byte[] sig2 = new byte[SIGNATURE_SIZE];
        // 填充非零数据以便验证往返
        for (int i = 0; i < SIGNATURE_SIZE; i++) {
            sig1[i] = (byte) (i + 1);
            sig2[i] = (byte) (i + 65);
        }
        long timestamp = 1700000000000L;

        ChannelUpdate original = new ChannelUpdate(
                CHANNEL_ID, 3L, 750L, 250L,
                sig1, sig2, timestamp
        );

        // 序列化为 JSON
        String json = original.toJson();
        assertNotNull("JSON 不应为 null", json);
        assertTrue("JSON 应包含 channelId", json.contains(CHANNEL_ID));

        // 反序列化
        ChannelUpdate restored = ChannelUpdate.fromJson(json);

        // 验证往返后字段一致
        assertEquals("channelId 往返一致", original.getChannelId(), restored.getChannelId());
        assertEquals("nonce 往返一致", original.getNonce(), restored.getNonce());
        assertEquals("balance1 往返一致", original.getBalance1(), restored.getBalance1());
        assertEquals("balance2 往返一致", original.getBalance2(), restored.getBalance2());
        assertEquals("timestamp 往返一致", original.getTimestamp(), restored.getTimestamp());

        // 验证签名往返一致
        assertArrayEquals("signature1 往返一致", original.getSignature1(), restored.getSignature1());
        assertArrayEquals("signature2 往返一致", original.getSignature2(), restored.getSignature2());

        // 验证往返后签名状态正确
        assertTrue("往返后应仍为已完全签名", restored.isFullySigned());
    }
}

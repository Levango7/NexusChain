package org.nexus.payment;

import org.nexus.core.payment.ChannelManager;
import org.nexus.core.payment.ChannelUpdate;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.crypto.HashUtil;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 通道管理器完整流程测试。
 *
 * <p>测试 ChannelManager 的通道开启、链下支付发起、确认、更新提交，
 * 以及对非法 nonce、余额不守恒和未签名更新的拒绝逻辑。
 * 使用真实 Ed25519 密钥对进行签名验证。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class ChannelManagerTest {

    /** 测试用参与方一地址。 */
    private static final String PARTICIPANT_1 = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    /** 测试用参与方二地址。 */
    private static final String PARTICIPANT_2 = "f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5";
    /** 测试用通道初始金额（NEX 最小单位）。 */
    private static final long INITIAL_AMOUNT = 1000L;
    /** 测试用锁定时间（区块高度）。 */
    private static final int LOCK_TIME = 10000;

    // ==================== 辅助方法 ====================

    /**
     * 生成两对 Ed25519 密钥用于测试。
     */
    private Ed25519KeyPair[] generateTwoKeyPairs() {
        return new Ed25519KeyPair[]{
                Ed25519.generateKeyPair(),
                Ed25519.generateKeyPair()
        };
    }

    /**
     * 用私钥对 ChannelUpdate 消息哈希签名。
     */
    private byte[] signUpdate(ChannelUpdate update, byte[] privateKeyBytes) {
        Ed25519PrivateKey privateKey = new Ed25519PrivateKey(privateKeyBytes);
        byte[] messageHash = HashUtil.keccak256(update.getMessageToSign());
        return privateKey.sign(messageHash);
    }

    /**
     * 构造一个双方签名的 ChannelUpdate（用于 submitUpdate 测试）。
     */
    private ChannelUpdate createSignedUpdate(String channelId, long nonce, long balance1, long balance2,
                                             Ed25519KeyPair keyPair1, Ed25519KeyPair keyPair2) {
        ChannelUpdate update = new ChannelUpdate(
                channelId, nonce, balance1, balance2,
                null, null, System.currentTimeMillis()
        );
        byte[] sig1 = signUpdate(update, keyPair1.getPrivateKey().getEncoded());
        byte[] sig2 = signUpdate(update, keyPair2.getPrivateKey().getEncoded());
        update.setSignature1(sig1);
        update.setSignature2(sig2);
        return update;
    }

    // ==================== 测试方法 ====================

    /**
     * 测试创建通道，验证 channelId 非空、状态为 OPEN。
     */
    @Test
    public void testOpenChannel() {
        ChannelManager manager = new ChannelManager();

        // 开启支付通道
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );

        // 验证 channelId 非空
        assertNotNull("channelId 不应为 null", channel.getChannelId());
        assertTrue("channelId 应非空字符串", !channel.getChannelId().isEmpty());

        // 验证通道状态为 OPEN
        assertEquals("通道状态应为 OPEN", PaymentChannel.State.OPEN, channel.getState());

        // 验证通道参数
        assertEquals("参与方一地址应一致", PARTICIPANT_1, channel.getParticipant1());
        assertEquals("参与方二地址应一致", PARTICIPANT_2, channel.getParticipant2());
        assertEquals("参与方一余额应等于初始注资", INITIAL_AMOUNT, channel.getBalance1());
        assertEquals("参与方二余额应为 0", 0L, channel.getBalance2());
        assertEquals("初始 nonce 应为 0", 0L, channel.getNonce());
        assertEquals("lockTime 应一致", LOCK_TIME, channel.getLockTime());

        // 验证通道已存储在管理器中
        PaymentChannel stored = manager.getChannel(channel.getChannelId());
        assertNotNull("管理器应能查到该通道", stored);
        assertEquals("存储的通道应与返回的一致", channel.getChannelId(), stored.getChannelId());
    }

    /**
     * 测试模拟发起支付，验证余额变化和签名。
     */
    @Test
    public void testInitiatePayment() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();

        // 开启通道
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );

        // 发起链下支付：参与方一向参与方二支付 100
        long paymentAmount = 100L;
        ChannelUpdate update = manager.initiatePayment(
                channel.getChannelId(), paymentAmount,
                keys[0].getPrivateKey().getEncoded(),
                keys[0].getPublicKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        );

        // 验证 update 基本信息
        assertNotNull("返回的 update 不应为 null", update);
        assertEquals("nonce 应为 1", 1L, update.getNonce());
        assertEquals("balance1 应减少", INITIAL_AMOUNT - paymentAmount, update.getBalance1());
        assertEquals("balance2 应增加", 0L + paymentAmount, update.getBalance2());

        // 验证发送方签名存在
        assertTrue("发送方签名应存在", update.hasSignature1());
        // 接收方尚未签名
        assertFalse("接收方签名应不存在", update.hasSignature2());
        assertFalse("不应视为已完全签名", update.isFullySigned());

        // 验证通道余额尚未更新（initiatePayment 不修改通道余额）
        PaymentChannel currentChannel = manager.getChannel(channel.getChannelId());
        assertEquals("通道余额1 不应改变", INITIAL_AMOUNT, currentChannel.getBalance1());
        assertEquals("通道余额2 不应改变", 0L, currentChannel.getBalance2());
        assertEquals("通道 nonce 不应改变", 0L, currentChannel.getNonce());
    }

    /**
     * 测试模拟确认支付，验证双方签名和余额更新。
     */
    @Test
    public void testConfirmPayment() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();

        // 开启通道
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );

        // 发起支付
        long paymentAmount = 100L;
        ChannelUpdate update = manager.initiatePayment(
                channel.getChannelId(), paymentAmount,
                keys[0].getPrivateKey().getEncoded(),
                keys[0].getPublicKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        );

        // 确认支付（接收方签名）
        ChannelUpdate confirmedUpdate = manager.confirmPayment(
                update,
                keys[1].getPrivateKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        );

        // 验证双方签名都存在
        assertTrue("发送方签名应存在", confirmedUpdate.hasSignature1());
        assertTrue("接收方签名应存在", confirmedUpdate.hasSignature2());
        assertTrue("应视为已完全签名", confirmedUpdate.isFullySigned());

        // 验证通道余额已更新
        PaymentChannel updatedChannel = manager.getChannel(channel.getChannelId());
        assertEquals("通道余额1 应更新", INITIAL_AMOUNT - paymentAmount, updatedChannel.getBalance1());
        assertEquals("通道余额2 应更新", paymentAmount, updatedChannel.getBalance2());
        assertEquals("通道 nonce 应递增到 1", 1L, updatedChannel.getNonce());
    }

    /**
     * 测试提交更新，验证 nonce 递增和余额守恒。
     */
    @Test
    public void testSubmitUpdate() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();

        // 开启通道（初始余额 1000, 0, nonce=0）
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );

        // 手动构造双方签名的 update（nonce 将为 1）
        long expectedNonce = channel.getNonce() + 1;
        long newBalance1 = 900L;
        long newBalance2 = 100L;
        ChannelUpdate signedUpdate = createSignedUpdate(
                channel.getChannelId(), expectedNonce, newBalance1, newBalance2,
                keys[0], keys[1]
        );

        // 提交更新
        ChannelUpdate result = manager.submitUpdate(
                channel.getChannelId(), newBalance1, newBalance2,
                signedUpdate.getSignature1(), signedUpdate.getSignature2(),
                keys[0].getPublicKey().getEncoded(), keys[1].getPublicKey().getEncoded()
        );

        // 验证 nonce 递增
        assertEquals("返回的 update nonce 应为 1", expectedNonce, result.getNonce());

        // 验证通道状态已更新
        PaymentChannel updatedChannel = manager.getChannel(channel.getChannelId());
        assertEquals("通道 nonce 应递增到 1", expectedNonce, updatedChannel.getNonce());
        assertEquals("通道余额1 应更新", newBalance1, updatedChannel.getBalance1());
        assertEquals("通道余额2 应更新", newBalance2, updatedChannel.getBalance2());
        assertEquals("余额守恒：总额不变", INITIAL_AMOUNT, updatedChannel.getTotalBalance());

        // 验证最新链下状态已存储
        ChannelUpdate latest = manager.getLatestUpdate(channel.getChannelId());
        assertNotNull("应有最新 update 记录", latest);
        assertEquals("最新 update nonce 应为 1", expectedNonce, latest.getNonce());
    }

    /**
     * 测试拒绝低 nonce 更新。
     */
    @Test
    public void testRejectInvalidNonce() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();

        // 开启通道
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );

        // 先完成一次支付，nonce 变为 1
        ChannelUpdate update1 = manager.initiatePayment(
                channel.getChannelId(), 100L,
                keys[0].getPrivateKey().getEncoded(),
                keys[0].getPublicKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        );
        manager.confirmPayment(update1,
                keys[1].getPrivateKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        );

        // 尝试构造一个 nonce 为 1（等于当前）的 update 并确认 — 应被拒绝
        ChannelUpdate staleUpdate = new ChannelUpdate(
                channel.getChannelId(), 1L, 800L, 200L,
                new byte[64], new byte[64], System.currentTimeMillis()
        );

        try {
            manager.confirmPayment(staleUpdate,
                    keys[1].getPrivateKey().getEncoded(),
                    keys[1].getPublicKey().getEncoded()
            );
            fail("应拒绝 nonce 不递增的更新");
        } catch (IllegalArgumentException e) {
            // 期望抛出异常
            assertTrue("异常消息应包含 nonce", e.getMessage().contains("Nonce"));
        }
    }

    /**
     * 测试拒绝余额不守恒的更新。
     */
    @Test
    public void testRejectUnbalancedUpdate() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();

        // 开启通道，总额为 1000
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );

        // 构造余额不守恒的签名 update（总额 999 != 1000）
        long expectedNonce = 1L;
        long badBalance1 = 899L;
        long badBalance2 = 100L; // 899 + 100 = 999 != 1000
        ChannelUpdate badUpdate = createSignedUpdate(
                channel.getChannelId(), expectedNonce, badBalance1, badBalance2,
                keys[0], keys[1]
        );

        try {
            manager.submitUpdate(
                    channel.getChannelId(), badBalance1, badBalance2,
                    badUpdate.getSignature1(), badUpdate.getSignature2(),
                    keys[0].getPublicKey().getEncoded(), keys[1].getPublicKey().getEncoded()
            );
            fail("应拒绝余额不守恒的更新");
        } catch (IllegalArgumentException e) {
            // 期望抛出异常
            assertTrue("异常消息应包含余额守恒信息", e.getMessage().contains("Balance"));
        }

        // 验证通道状态未被修改
        PaymentChannel unchanged = manager.getChannel(channel.getChannelId());
        assertEquals("通道余额1 不应改变", INITIAL_AMOUNT, unchanged.getBalance1());
        assertEquals("通道 nonce 不应改变", 0L, unchanged.getNonce());
    }

    /**
     * 测试拒绝未签名的更新。
     */
    @Test
    public void testRejectUnsignedUpdate() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();

        // 开启通道
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );

        // 使用全零签名（无效签名）提交更新
        byte[] fakeSig1 = new byte[64];
        byte[] fakeSig2 = new byte[64];

        try {
            manager.submitUpdate(
                    channel.getChannelId(), 900L, 100L,
                    fakeSig1, fakeSig2,
                    keys[0].getPublicKey().getEncoded(), keys[1].getPublicKey().getEncoded()
            );
            fail("应拒绝无效签名的更新");
        } catch (IllegalArgumentException e) {
            // 期望抛出异常（签名验证失败）
            assertTrue("异常消息应包含签名信息", e.getMessage().contains("Signature"));
        }

        // 验证通道状态未被修改
        PaymentChannel unchanged = manager.getChannel(channel.getChannelId());
        assertEquals("通道余额1 不应改变", INITIAL_AMOUNT, unchanged.getBalance1());
        assertEquals("通道 nonce 不应改变", 0L, unchanged.getNonce());
    }

    /**
     * 测试完整支付周期：open → initiate → confirm → verify。
     */
    @Test
    public void testFullPaymentCycle() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();

        // 步骤1：开启通道
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );
        assertEquals("初始状态应为 OPEN", PaymentChannel.State.OPEN, channel.getState());
        assertEquals("初始 nonce 应为 0", 0L, channel.getNonce());
        assertEquals("初始总额应为 1000", INITIAL_AMOUNT, channel.getTotalBalance());

        // 步骤2：发起第一次支付 100
        ChannelUpdate update1 = manager.initiatePayment(
                channel.getChannelId(), 100L,
                keys[0].getPrivateKey().getEncoded(),
                keys[0].getPublicKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        );
        assertEquals("第一次支付 nonce 应为 1", 1L, update1.getNonce());
        assertTrue("发送方签名应存在", update1.hasSignature1());

        // 步骤3：确认第一次支付
        manager.confirmPayment(update1,
                keys[1].getPrivateKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        );
        assertEquals("确认后 nonce 应为 1", 1L, channel.getNonce());
        assertEquals("确认后余额1 应为 900", 900L, channel.getBalance1());
        assertEquals("确认后余额2 应为 100", 100L, channel.getBalance2());
        assertEquals("余额守恒", INITIAL_AMOUNT, channel.getTotalBalance());

        // 步骤4：发起第二次支付 50（参与方一向参与方二）
        ChannelUpdate update2 = manager.initiatePayment(
                channel.getChannelId(), 50L,
                keys[0].getPrivateKey().getEncoded(),
                keys[0].getPublicKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        );
        assertEquals("第二次支付 nonce 应为 2", 2L, update2.getNonce());

        // 步骤5：确认第二次支付
        manager.confirmPayment(update2,
                keys[1].getPrivateKey().getEncoded(),
                keys[1].getPublicKey().getEncoded()
        );
        assertEquals("第二次确认后 nonce 应为 2", 2L, channel.getNonce());
        assertEquals("第二次确认后余额1 应为 850", 850L, channel.getBalance1());
        assertEquals("第二次确认后余额2 应为 150", 150L, channel.getBalance2());
        assertEquals("余额守恒", INITIAL_AMOUNT, channel.getTotalBalance());

        // 验证最终状态
        assertEquals("通道最终状态应为 OPEN", PaymentChannel.State.OPEN, channel.getState());
    }
}

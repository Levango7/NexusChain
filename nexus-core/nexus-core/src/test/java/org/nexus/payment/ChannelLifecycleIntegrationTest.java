package org.nexus.payment;

import org.nexus.core.TransactionPool;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.ChannelUpdate;
import org.nexus.core.payment.ChannelManager;
import org.nexus.core.payment.DisputeRecord;
import org.nexus.core.payment.DisputeResolution;
import org.nexus.core.payment.DisputeSettlement;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.crypto.HashUtil;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端到端通道生命周期集成测试。
 *
 * <p>验证支付通道从开启到关闭的完整生命周期，包括：
 * <ul>
 *   <li>协作关闭生命周期（多次链下支付 → 协作关闭）</li>
 *   <li>争议生命周期（链下支付 → 发起争议 → 挑战 → 结算）</li>
 *   <li>过期生命周期（无活动 → 锁定期到期 → 强制过期）</li>
 *   <li>多支付场景（一个通道内多次双向链下支付）</li>
 * </ul></p>
 *
 * <p>使用真实 Ed25519 密钥对进行签名验证，不依赖 Spring 容器。
 * 由于 ChannelSettlementService 尚未实现，协作关闭和强制过期
 * 通过直接调用 PaymentChannel 状态转换方法模拟。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class ChannelLifecycleIntegrationTest {

    /** 测试用参与方一地址。 */
    private static final String PARTICIPANT_1 = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    /** 测试用参与方二地址。 */
    private static final String PARTICIPANT_2 = "f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5";
    /** 测试用通道初始金额（NEX 最小单位）。 */
    private static final long INITIAL_AMOUNT = 1000L;
    /** 测试用锁定时间。 */
    private static final int LOCK_TIME = 10000;
    /** 争议期长度。 */
    private static final int DISPUTE_PERIOD = 100;
    /** 公钥长度。 */
    private static final int PUBKEY_SIZE = Transaction.PUBLIC_KEY_SIZE;
    /** 签名长度。 */
    private static final int SIG_SIZE = Transaction.SIGNATURE_SIZE;

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
     * 构造一个双方签名的 ChannelUpdate（用于 submitUpdate）。
     */
    private ChannelUpdate createSignedUpdate(String channelId, long nonce, long balance1, long balance2,
                                             Ed25519KeyPair keyPair1, Ed25519KeyPair keyPair2) {
        ChannelUpdate update = new ChannelUpdate(
                channelId, nonce, balance1, balance2,
                null, null, System.currentTimeMillis()
        );
        update.setSignature1(signUpdate(update, keyPair1.getPrivateKey().getEncoded()));
        update.setSignature2(signUpdate(update, keyPair2.getPrivateKey().getEncoded()));
        return update;
    }

    /**
     * 创建一个双方已签名（骨架签名）的 ChannelUpdate，用于争议流程。
     */
    private ChannelUpdate createDisputeUpdate(String channelId, long nonce, long balance1, long balance2) {
        return new ChannelUpdate(
                channelId, nonce, balance1, balance2,
                new byte[SIG_SIZE], new byte[SIG_SIZE],
                System.currentTimeMillis()
        );
    }

    /**
     * 通过 submitUpdate 执行一次链下支付（支持双向）。
     *
     * @param manager    通道管理器
     * @param channelId  通道 ID
     * @param balance1   目标余额1
     * @param balance2   目标余额2
     * @param keys       密钥对
     * @return 已确认的 ChannelUpdate
     */
    private ChannelUpdate performOffChainPayment(ChannelManager manager, String channelId,
                                                  long balance1, long balance2,
                                                  Ed25519KeyPair[] keys) {
        PaymentChannel channel = manager.getChannel(channelId);
        long expectedNonce = channel.getNonce() + 1;
        ChannelUpdate signedUpdate = createSignedUpdate(
                channelId, expectedNonce, balance1, balance2,
                keys[0], keys[1]
        );
        return manager.submitUpdate(
                channelId, balance1, balance2,
                signedUpdate.getSignature1(), signedUpdate.getSignature2(),
                keys[0].getPublicKey().getEncoded(), keys[1].getPublicKey().getEncoded()
        );
    }

    // ==================== 端到端测试 ====================

    /**
     * 测试协作关闭生命周期：open → 多次链下支付 → 协作关闭。
     */
    @Test
    public void testCooperativeLifecycle() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();

        // 步骤1：开启通道
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );
        assertEquals(PaymentChannel.State.OPEN, channel.getState(), "初始状态应为 OPEN");
        assertEquals(0L, channel.getNonce(), "初始 nonce 应为 0");
        long totalBalance = channel.getTotalBalance();

        // 步骤2：多次链下支付
        // 支付1：p1 → p2，金额 100 → 900/100
        performOffChainPayment(manager, channel.getChannelId(), 900L, 100L, keys);
        PaymentChannel ch1 = manager.getChannel(channel.getChannelId());
        assertEquals(900L, ch1.getBalance1(), "支付1后余额1 应为 900");
        assertEquals(100L, ch1.getBalance2(), "支付1后余额2 应为 100");
        assertEquals(1L, ch1.getNonce(), "支付1后 nonce 应为 1");
        assertEquals(totalBalance, ch1.getTotalBalance(), "余额守恒");

        // 支付2：p2 → p1，金额 50 → 950/50
        performOffChainPayment(manager, channel.getChannelId(), 950L, 50L, keys);
        PaymentChannel ch2 = manager.getChannel(channel.getChannelId());
        assertEquals(950L, ch2.getBalance1(), "支付2后余额1 应为 950");
        assertEquals(50L, ch2.getBalance2(), "支付2后余额2 应为 50");
        assertEquals(2L, ch2.getNonce(), "支付2后 nonce 应为 2");
        assertEquals(totalBalance, ch2.getTotalBalance(), "余额守恒");

        // 支付3：p1 → p2，金额 200 → 750/250
        performOffChainPayment(manager, channel.getChannelId(), 750L, 250L, keys);
        PaymentChannel ch3 = manager.getChannel(channel.getChannelId());
        assertEquals(750L, ch3.getBalance1(), "支付3后余额1 应为 750");
        assertEquals(250L, ch3.getBalance2(), "支付3后余额2 应为 250");
        assertEquals(3L, ch3.getNonce(), "支付3后 nonce 应为 3");
        assertEquals(totalBalance, ch3.getTotalBalance(), "余额守恒");

        // 步骤3：获取最终余额
        long finalBalance1 = ch3.getBalance1();
        long finalBalance2 = ch3.getBalance2();
        assertEquals(totalBalance, finalBalance1 + finalBalance2, "最终总额应守恒");

        // 步骤4：协作关闭（模拟 ChannelSettlementService.cooperativeClose）
        // 双方同意关闭，请求关闭通道
        long closeBlockHeight = 500L;
        ch3.requestClose(closeBlockHeight);
        assertEquals(PaymentChannel.State.CLOSING, ch3.getState(), "请求关闭后状态应为 CLOSING");

        // 等待争议期过后
        long afterDisputeBlock = closeBlockHeight + ch3.getDisputePeriod() + 1;
        ch3.close(afterDisputeBlock);
        assertEquals(PaymentChannel.State.CLOSED, ch3.getState(), "协作关闭后状态应为 CLOSED");

        // 验证最终余额在关闭后不变
        assertEquals(finalBalance1, ch3.getBalance1(), "关闭后余额1 应不变");
        assertEquals(finalBalance2, ch3.getBalance2(), "关闭后余额2 应不变");
    }

    /**
     * 测试争议生命周期：open → 链下支付 → 争议 → 挑战 → 结算。
     */
    @Test
    public void testDisputeLifecycle() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();
        TransactionPool txPool = new TransactionPool();
        DisputeResolution disputeResolution = new DisputeResolution(txPool);

        // 步骤1：开启通道
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );
        assertEquals(PaymentChannel.State.OPEN, channel.getState(), "初始状态应为 OPEN");

        // 步骤2：发起多次链下支付
        // 支付1：p1 → p2，金额 100 → 900/100
        performOffChainPayment(manager, channel.getChannelId(), 900L, 100L, keys);
        // 支付2：p1 → p2，金额 50 → 850/150
        performOffChainPayment(manager, channel.getChannelId(), 850L, 150L, keys);

        PaymentChannel afterPayments = manager.getChannel(channel.getChannelId());
        assertEquals(2L, afterPayments.getNonce(), "两次支付后 nonce 应为 2");
        assertEquals(850L, afterPayments.getBalance1(), "两次支付后余额1 应为 850");
        assertEquals(150L, afterPayments.getBalance2(), "两次支付后余额2 应为 150");

        // 步骤3：发起争议（提交旧状态 nonce=1，余额 900/100）
        // 模拟发起方恶意提交过期状态
        ChannelUpdate staleUpdate = createDisputeUpdate(
                channel.getChannelId(), 1L, 900L, 100L
        );
        long disputeStartBlock = 2000L;
        DisputeRecord dispute = disputeResolution.initiateDispute(
                channel.getChannelId(), staleUpdate,
                new byte[PUBKEY_SIZE], new byte[SIG_SIZE],
                disputeStartBlock, DISPUTE_PERIOD
        );
        assertNotNull(dispute, "争议记录应创建");
        assertEquals(DisputeRecord.DisputeState.ACTIVE, dispute.getState(), "争议状态应为 ACTIVE");
        assertEquals(1L, dispute.getLatestUpdate().getNonce(), "争议 nonce 应为 1（旧状态）");

        // 步骤4：挑战争议（提交新状态 nonce=2，余额 850/150）
        ChannelUpdate freshUpdate = createDisputeUpdate(
                channel.getChannelId(), 2L, 850L, 150L
        );
        long challengeBlock = disputeStartBlock + 30;
        DisputeRecord challenged = disputeResolution.challengeDispute(
                channel.getChannelId(), freshUpdate,
                new byte[PUBKEY_SIZE], new byte[SIG_SIZE],
                challengeBlock
        );
        assertEquals(DisputeRecord.DisputeState.CHALLENGED, challenged.getState(), "挑战后状态应为 CHALLENGED");
        assertEquals(2L, challenged.getLatestUpdate().getNonce(), "挑战后 nonce 应为 2");
        assertTrue(challenged.getPenaltyAmount() > 0, "应有惩罚金");

        // 步骤5：争议期过后结算
        long settleBlock = disputeStartBlock + DISPUTE_PERIOD + 1;
        DisputeSettlement settlement = disputeResolution.settleDispute(
                channel.getChannelId(), settleBlock
        );
        assertNotNull(settlement, "结算结果不应为 null");

        // 步骤6：验证最终余额
        // 惩罚金从发起方（参与方一）余额扣除
        long penalty = challenged.getPenaltyAmount();
        assertEquals(850L - penalty, settlement.getFinalBalance1(), "最终余额1 应为 850 减惩罚金");
        assertEquals(150L, settlement.getFinalBalance2(), "最终余额2 应为 150");
        assertTrue(settlement.getPenaltyAmount() > 0, "惩罚金应大于 0");

        // 验证争议状态已结算
        DisputeRecord finalRecord = disputeResolution.getDisputeStatus(channel.getChannelId());
        assertEquals(DisputeRecord.DisputeState.SETTLED, finalRecord.getState(), "最终争议状态应为 SETTLED");
    }

    /**
     * 测试过期生命周期：open → 无活动 → 过期。
     */
    @Test
    public void testExpiredChannelLifecycle() {
        ChannelManager manager = new ChannelManager();

        // 步骤1：开启通道，设置 lockTime
        int lockTime = 500;
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, lockTime
        );
        assertEquals(PaymentChannel.State.OPEN, channel.getState(), "初始状态应为 OPEN");
        assertEquals(lockTime, channel.getLockTime(), "lockTime 应为 500");

        // 步骤2：模拟锁定期到期（当前区块高度 >= lockTime）
        long currentBlockHeight = lockTime; // 恰好等于 lockTime

        // 步骤3：强制过期（模拟 ChannelSettlementService.forceExpire）
        // PaymentChannel.expire 要求 currentBlockHeight >= lockTime
        PaymentChannel.State expiredState = channel.expire(currentBlockHeight);

        // 步骤4：验证状态为 EXPIRED
        assertEquals(PaymentChannel.State.EXPIRED, expiredState, "过期后状态应为 EXPIRED");
        assertEquals(PaymentChannel.State.EXPIRED, channel.getState(), "通道状态应为 EXPIRED");

        // 验证过期通道无法再更新
        try {
            channel.update(800L, 200L, 1L);
            fail("过期通道不应能更新");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("OPEN"), "异常消息应包含状态");
        }
    }

    /**
     * 测试一个通道内多次链下支付，验证余额守恒和 nonce 递增。
     */
    @Test
    public void testMultiplePaymentsInChannel() {
        ChannelManager manager = new ChannelManager();
        Ed25519KeyPair[] keys = generateTwoKeyPairs();

        // 步骤1：开启通道，总余额 = 1000
        PaymentChannel channel = manager.openChannel(
                PARTICIPANT_1, PARTICIPANT_2, INITIAL_AMOUNT, LOCK_TIME
        );
        long totalBalance = channel.getTotalBalance();
        assertEquals(1000L, totalBalance, "通道总余额应为 1000");
        assertEquals(0L, channel.getNonce(), "初始 nonce 应为 0");
        assertEquals(1000L, channel.getBalance1(), "初始余额1 应为 1000");
        assertEquals(0L, channel.getBalance2(), "初始余额2 应为 0");

        // 支付1：100 from p1 to p2 → 余额 900/100
        performOffChainPayment(manager, channel.getChannelId(), 900L, 100L, keys);
        PaymentChannel after1 = manager.getChannel(channel.getChannelId());
        assertEquals(900L, after1.getBalance1(), "支付1后余额1 应为 900");
        assertEquals(100L, after1.getBalance2(), "支付1后余额2 应为 100");
        assertEquals(1L, after1.getNonce(), "支付1后 nonce 应为 1");
        assertEquals(totalBalance, after1.getTotalBalance(), "支付1后总额应守恒");

        // 支付2：50 from p2 to p1 → 余额 950/50
        performOffChainPayment(manager, channel.getChannelId(), 950L, 50L, keys);
        PaymentChannel after2 = manager.getChannel(channel.getChannelId());
        assertEquals(950L, after2.getBalance1(), "支付2后余额1 应为 950");
        assertEquals(50L, after2.getBalance2(), "支付2后余额2 应为 50");
        assertEquals(2L, after2.getNonce(), "支付2后 nonce 应为 2");
        assertEquals(totalBalance, after2.getTotalBalance(), "支付2后总额应守恒");

        // 支付3：200 from p1 to p2 → 余额 750/250
        performOffChainPayment(manager, channel.getChannelId(), 750L, 250L, keys);
        PaymentChannel after3 = manager.getChannel(channel.getChannelId());
        assertEquals(750L, after3.getBalance1(), "支付3后余额1 应为 750");
        assertEquals(250L, after3.getBalance2(), "支付3后余额2 应为 250");
        assertEquals(3L, after3.getNonce(), "支付3后 nonce 应为 3");
        assertEquals(totalBalance, after3.getTotalBalance(), "支付3后总额应守恒");

        // 步骤5：验证每次支付后总额守恒（1000）
        assertEquals(1000L, after3.getTotalBalance(), "最终总额应仍为 1000");

        // 步骤6：验证 nonce 递增（0→1→2→3）
        assertEquals(3L, after3.getNonce(), "最终 nonce 应为 3");

        // 验证最终余额正确
        assertEquals(750L, after3.getBalance1(), "最终余额1 应为 750");
        assertEquals(250L, after3.getBalance2(), "最终余额2 应为 250");
    }
}

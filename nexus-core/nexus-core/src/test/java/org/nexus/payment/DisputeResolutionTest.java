package org.nexus.payment;

import org.nexus.core.TransactionPool;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.ChannelUpdate;
import org.nexus.core.payment.DisputeRecord;
import org.nexus.core.payment.DisputeResolution;
import org.nexus.core.payment.DisputeSettlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 争议解决流程测试。
 *
 * <p>测试 DisputeResolution 的发起争议、挑战争议、过期拒绝挑战、
 * 争议结算、惩罚金计算以及完整争议流程。</p>
 *
 * <p>DisputeResolution 依赖 TransactionPool，测试中直接 new TransactionPool()。
 * 签名相关参数使用正确长度的全零字节数组作为骨架，因为发起和挑战
 * 只检查签名大小和 isFullySigned 状态，不执行实际密码学验证。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class DisputeResolutionTest {

    /** 测试用通道 ID。 */
    private static final String CHANNEL_ID = "nexus-dispute-ch-001";
    /** 争议期长度（区块数）。 */
    private static final int DISPUTE_PERIOD = 100;
    /** 争议开始区块高度。 */
    private static final long START_BLOCK = 1000L;
    /** 公钥长度。 */
    private static final int PUBKEY_SIZE = Transaction.PUBLIC_KEY_SIZE; // 32
    /** 签名长度。 */
    private static final int SIG_SIZE = Transaction.SIGNATURE_SIZE; // 64

    /** 争议解决实例。 */
    private DisputeResolution disputeResolution;
    /** 全零公钥（骨架）。 */
    private final byte[] dummyPubkey = new byte[PUBKEY_SIZE];
    /** 全零签名（骨架）。 */
    private final byte[] dummySig = new byte[SIG_SIZE];

    /**
     * 每个测试前创建新的 DisputeResolution 实例。
     */
    @BeforeEach
    public void setUp() {
        // 直接 new TransactionPool，不依赖 Spring 容器
        disputeResolution = new DisputeResolution(new TransactionPool());
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个双方已签名（全零骨架签名）的 ChannelUpdate。
     *
     * @param nonce    更新序号
     * @param balance1 参与方一余额
     * @param balance2 参与方二余额
     * @return 已完全签名的 ChannelUpdate
     */
    private ChannelUpdate createFullySignedUpdate(long nonce, long balance1, long balance2) {
        return new ChannelUpdate(
                CHANNEL_ID, nonce, balance1, balance2,
                new byte[SIG_SIZE], new byte[SIG_SIZE],
                System.currentTimeMillis()
        );
    }

    // ==================== 测试方法 ====================

    /**
     * 测试发起争议，验证 DisputeRecord 创建。
     */
    @Test
    public void testInitiateDispute() {
        // 创建一个已完全签名的链下状态更新（模拟发起方持有的最新状态）
        ChannelUpdate latestUpdate = createFullySignedUpdate(1L, 800L, 200L);

        // 发起争议
        DisputeRecord record = disputeResolution.initiateDispute(
                CHANNEL_ID, latestUpdate,
                dummyPubkey, dummySig,
                START_BLOCK, DISPUTE_PERIOD
        );

        // 验证争议记录创建
        assertNotNull(record, "争议记录不应为 null");
        assertEquals(CHANNEL_ID, record.getChannelId(), "channelId 应一致");
        assertEquals(START_BLOCK, record.getStartBlock(), "起始区块应一致");
        assertEquals(START_BLOCK + DISPUTE_PERIOD, record.getEndBlock(), "结束区块应为 start + disputePeriod");
        assertEquals(DisputeRecord.DisputeState.ACTIVE, record.getState(), "争议状态应为 ACTIVE");
        assertEquals(0L, record.getPenaltyAmount(), "惩罚金初始应为 0");
        assertNotNull(record.getLatestUpdate(), "最新 update 不应为 null");
        assertEquals(1L, record.getLatestUpdate().getNonce(), "latestUpdate nonce 应为 1");

        // 验证争议可查询
        DisputeRecord queried = disputeResolution.getDisputeStatus(CHANNEL_ID);
        assertNotNull(queried, "应能查询到争议记录");
        assertEquals(CHANNEL_ID, queried.getChannelId(), "查询到的记录 channelId 应一致");

        // 验证重复发起会抛异常
        try {
            disputeResolution.initiateDispute(
                    CHANNEL_ID, latestUpdate,
                    dummyPubkey, dummySig,
                    START_BLOCK, DISPUTE_PERIOD
            );
            fail("重复发起争议应抛出异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("already exists"), "异常消息应包含已存在");
        }
    }

    /**
     * 测试提交更高 nonce 的挑战。
     */
    @Test
    public void testChallengeDispute() {
        // 发起争议（提交 nonce=1 的旧状态）
        ChannelUpdate oldUpdate = createFullySignedUpdate(1L, 800L, 200L);
        disputeResolution.initiateDispute(
                CHANNEL_ID, oldUpdate,
                dummyPubkey, dummySig,
                START_BLOCK, DISPUTE_PERIOD
        );

        // 在争议期内提交更高 nonce 的挑战（nonce=2）
        ChannelUpdate newUpdate = createFullySignedUpdate(2L, 700L, 300L);
        long challengeBlock = START_BLOCK + 50; // 争议期内

        DisputeRecord challenged = disputeResolution.challengeDispute(
                CHANNEL_ID, newUpdate,
                dummyPubkey, dummySig,
                challengeBlock
        );

        // 验证挑战成功
        assertNotNull(challenged, "返回的争议记录不应为 null");
        assertEquals(DisputeRecord.DisputeState.CHALLENGED, challenged.getState(), "争议状态应为 CHALLENGED");
        assertEquals(2L, challenged.getLatestUpdate().getNonce(), "latestUpdate nonce 应更新为 2");
        assertEquals(700L, challenged.getLatestUpdate().getBalance1(), "latestUpdate balance1 应为 700");
        assertTrue(challenged.getPenaltyAmount() > 0, "惩罚金应大于 0");
    }

    /**
     * 测试争议期过后不能挑战。
     */
    @Test
    public void testRejectExpiredChallenge() {
        // 发起争议
        ChannelUpdate oldUpdate = createFullySignedUpdate(1L, 800L, 200L);
        disputeResolution.initiateDispute(
                CHANNEL_ID, oldUpdate,
                dummyPubkey, dummySig,
                START_BLOCK, DISPUTE_PERIOD
        );

        // 在争议期结束后尝试挑战
        ChannelUpdate newUpdate = createFullySignedUpdate(2L, 700L, 300L);
        long expiredBlock = START_BLOCK + DISPUTE_PERIOD; // 争议期已过

        try {
            disputeResolution.challengeDispute(
                    CHANNEL_ID, newUpdate,
                    dummyPubkey, dummySig,
                    expiredBlock
            );
            fail("争议期过后应不能挑战");
        } catch (IllegalArgumentException e) {
            // 期望抛出异常
            assertTrue(e.getMessage().contains("Dispute period has ended"), "异常消息应包含争议期结束");
        }

        // 验证争议记录状态已变为 EXPIRED
        DisputeRecord record = disputeResolution.getDisputeStatus(CHANNEL_ID);
        assertEquals(DisputeRecord.DisputeState.EXPIRED, record.getState(), "争议状态应为 EXPIRED");
    }

    /**
     * 测试争议期过后结算，验证结算结果。
     */
    @Test
    public void testSettleDispute() {
        // 发起争议
        ChannelUpdate oldUpdate = createFullySignedUpdate(1L, 800L, 200L);
        disputeResolution.initiateDispute(
                CHANNEL_ID, oldUpdate,
                dummyPubkey, dummySig,
                START_BLOCK, DISPUTE_PERIOD
        );

        // 争议期结束后结算
        long settleBlock = START_BLOCK + DISPUTE_PERIOD + 1;
        DisputeSettlement settlement = disputeResolution.settleDispute(CHANNEL_ID, settleBlock);

        // 验证结算结果
        assertNotNull(settlement, "结算结果不应为 null");
        assertEquals(CHANNEL_ID, settlement.getChannelId(), "channelId 应一致");
        assertEquals(800L, settlement.getFinalBalance1(), "最终余额1 应为 800");
        assertEquals(200L, settlement.getFinalBalance2(), "最终余额2 应为 200");
        assertEquals(0L, settlement.getPenaltyAmount(), "惩罚金应为 0（无挑战）");
        assertEquals(DisputeSettlement.WINNER_DRAW, settlement.getWinner(), "获胜方应为平局（无挑战）");
        assertEquals(settleBlock, settlement.getSettledBlock(), "结算区块应一致");

        // 验证争议状态已变为 SETTLED
        DisputeRecord record = disputeResolution.getDisputeStatus(CHANNEL_ID);
        assertEquals(DisputeRecord.DisputeState.SETTLED, record.getState(), "争议状态应为 SETTLED");
    }

    /**
     * 测试争议期未过时不能结算。
     */
    @Test
    public void testSettleBeforeDisputePeriodEnds() {
        // 发起争议
        ChannelUpdate oldUpdate = createFullySignedUpdate(1L, 800L, 200L);
        disputeResolution.initiateDispute(
                CHANNEL_ID, oldUpdate,
                dummyPubkey, dummySig,
                START_BLOCK, DISPUTE_PERIOD
        );

        // 争议期内尝试结算 — 应失败
        long earlyBlock = START_BLOCK + 10;
        try {
            disputeResolution.settleDispute(CHANNEL_ID, earlyBlock);
            fail("争议期内不应能结算");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Dispute period has not ended"), "异常消息应包含争议期未结束");
        }
    }

    /**
     * 测试惩罚金计算。
     */
    @Test
    public void testCalculatePenalty() {
        // 创建争议记录，包含旧状态（发起方余额为 800）
        ChannelUpdate staleUpdate = createFullySignedUpdate(1L, 800L, 200L);
        DisputeRecord dispute = new DisputeRecord(
                CHANNEL_ID, START_BLOCK, START_BLOCK + DISPUTE_PERIOD,
                staleUpdate, "dummy-pubkey-hex",
                DisputeRecord.DisputeState.ACTIVE, 0L
        );

        // 创建更高 nonce 的挑战 update
        ChannelUpdate challengingUpdate = createFullySignedUpdate(2L, 700L, 300L);

        // 计算惩罚金
        long penalty = disputeResolution.calculatePenalty(dispute, challengingUpdate);

        // 惩罚金应为旧状态发起方余额的 10% = 800 / 10 = 80
        assertEquals(80L, penalty, "惩罚金应为发起方余额的 10%");
        assertTrue(penalty > 0, "惩罚金应大于 0");

        // 测试 nonce 不更高时惩罚金为 0
        ChannelUpdate sameNonceUpdate = createFullySignedUpdate(1L, 700L, 300L);
        long zeroPenalty = disputeResolution.calculatePenalty(dispute, sameNonceUpdate);
        assertEquals(0L, zeroPenalty, "nonce 不更高时惩罚金应为 0");

        // 测试更低 nonce 时惩罚金为 0
        ChannelUpdate lowerNonceUpdate = createFullySignedUpdate(0L, 700L, 300L);
        long lowerPenalty = disputeResolution.calculatePenalty(dispute, lowerNonceUpdate);
        assertEquals(0L, lowerPenalty, "nonce 更低时惩罚金应为 0");

        // 测试 null 参数返回 0
        assertEquals(0L, disputeResolution.calculatePenalty(null, challengingUpdate), "dispute 为 null 时应返回 0");
        assertEquals(0L, disputeResolution.calculatePenalty(dispute, null), "challengingUpdate 为 null 时应返回 0");
    }

    /**
     * 测试完整争议流程：initiate → challenge → settle。
     */
    @Test
    public void testFullDisputeFlow() {
        // 步骤1：发起争议（提交 nonce=1 的旧状态，余额 800/200）
        ChannelUpdate oldUpdate = createFullySignedUpdate(1L, 800L, 200L);
        DisputeRecord initiated = disputeResolution.initiateDispute(
                CHANNEL_ID, oldUpdate,
                dummyPubkey, dummySig,
                START_BLOCK, DISPUTE_PERIOD
        );
        assertEquals(DisputeRecord.DisputeState.ACTIVE, initiated.getState(), "初始争议状态应为 ACTIVE");
        assertEquals(1L, initiated.getLatestUpdate().getNonce(), "初始 nonce 应为 1");

        // 步骤2：挑战争议（提交 nonce=2 的新状态，余额 700/300）
        ChannelUpdate newUpdate = createFullySignedUpdate(2L, 700L, 300L);
        long challengeBlock = START_BLOCK + 30;
        DisputeRecord challenged = disputeResolution.challengeDispute(
                CHANNEL_ID, newUpdate,
                dummyPubkey, dummySig,
                challengeBlock
        );
        assertEquals(DisputeRecord.DisputeState.CHALLENGED, challenged.getState(), "挑战后状态应为 CHALLENGED");
        assertEquals(2L, challenged.getLatestUpdate().getNonce(), "挑战后 nonce 应为 2");
        assertEquals(700L, challenged.getLatestUpdate().getBalance1(), "挑战后余额1 应为 700");
        assertTrue(challenged.getPenaltyAmount() > 0, "挑战后应有惩罚金");

        // 步骤3：争议期结束后结算
        long settleBlock = START_BLOCK + DISPUTE_PERIOD + 1;
        DisputeSettlement settlement = disputeResolution.settleDispute(CHANNEL_ID, settleBlock);

        // 验证最终结算结果
        assertNotNull(settlement, "结算结果不应为 null");
        assertEquals(700L - challenged.getPenaltyAmount(), settlement.getFinalBalance1(), "最终余额1 应为 700 减惩罚金");
        assertEquals(300L, settlement.getFinalBalance2(), "最终余额2 应为 300");
        assertTrue(settlement.getPenaltyAmount() > 0, "惩罚金应大于 0");
        assertFalse(DisputeSettlement.WINNER_DRAW.equals(settlement.getWinner()), "获胜方不应为平局");

        // 验证争议状态已结算
        DisputeRecord finalRecord = disputeResolution.getDisputeStatus(CHANNEL_ID);
        assertEquals(DisputeRecord.DisputeState.SETTLED, finalRecord.getState(), "最终争议状态应为 SETTLED");
    }
}

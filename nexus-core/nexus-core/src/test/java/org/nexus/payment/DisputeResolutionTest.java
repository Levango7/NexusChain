package org.nexus.payment;

import org.nexus.core.TransactionPool;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.ChannelUpdate;
import org.nexus.core.payment.DisputeRecord;
import org.nexus.core.payment.DisputeResolution;
import org.nexus.core.payment.DisputeSettlement;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    @Before
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
        assertNotNull("争议记录不应为 null", record);
        assertEquals("channelId 应一致", CHANNEL_ID, record.getChannelId());
        assertEquals("起始区块应一致", START_BLOCK, record.getStartBlock());
        assertEquals("结束区块应为 start + disputePeriod",
                START_BLOCK + DISPUTE_PERIOD, record.getEndBlock());
        assertEquals("争议状态应为 ACTIVE", DisputeRecord.DisputeState.ACTIVE, record.getState());
        assertEquals("惩罚金初始应为 0", 0L, record.getPenaltyAmount());
        assertNotNull("最新 update 不应为 null", record.getLatestUpdate());
        assertEquals("latestUpdate nonce 应为 1", 1L, record.getLatestUpdate().getNonce());

        // 验证争议可查询
        DisputeRecord queried = disputeResolution.getDisputeStatus(CHANNEL_ID);
        assertNotNull("应能查询到争议记录", queried);
        assertEquals("查询到的记录 channelId 应一致", CHANNEL_ID, queried.getChannelId());

        // 验证重复发起会抛异常
        try {
            disputeResolution.initiateDispute(
                    CHANNEL_ID, latestUpdate,
                    dummyPubkey, dummySig,
                    START_BLOCK, DISPUTE_PERIOD
            );
            fail("重复发起争议应抛出异常");
        } catch (IllegalArgumentException e) {
            assertTrue("异常消息应包含已存在", e.getMessage().contains("already exists"));
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
        assertNotNull("返回的争议记录不应为 null", challenged);
        assertEquals("争议状态应为 CHALLENGED", DisputeRecord.DisputeState.CHALLENGED, challenged.getState());
        assertEquals("latestUpdate nonce 应更新为 2", 2L, challenged.getLatestUpdate().getNonce());
        assertEquals("latestUpdate balance1 应为 700", 700L, challenged.getLatestUpdate().getBalance1());
        assertTrue("惩罚金应大于 0", challenged.getPenaltyAmount() > 0);
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
            assertTrue("异常消息应包含争议期结束", e.getMessage().contains("Dispute period has ended"));
        }

        // 验证争议记录状态已变为 EXPIRED
        DisputeRecord record = disputeResolution.getDisputeStatus(CHANNEL_ID);
        assertEquals("争议状态应为 EXPIRED", DisputeRecord.DisputeState.EXPIRED, record.getState());
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
        assertNotNull("结算结果不应为 null", settlement);
        assertEquals("channelId 应一致", CHANNEL_ID, settlement.getChannelId());
        assertEquals("最终余额1 应为 800", 800L, settlement.getFinalBalance1());
        assertEquals("最终余额2 应为 200", 200L, settlement.getFinalBalance2());
        assertEquals("惩罚金应为 0（无挑战）", 0L, settlement.getPenaltyAmount());
        assertEquals("获胜方应为平局（无挑战）", DisputeSettlement.WINNER_DRAW, settlement.getWinner());
        assertEquals("结算区块应一致", settleBlock, settlement.getSettledBlock());

        // 验证争议状态已变为 SETTLED
        DisputeRecord record = disputeResolution.getDisputeStatus(CHANNEL_ID);
        assertEquals("争议状态应为 SETTLED", DisputeRecord.DisputeState.SETTLED, record.getState());
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
            assertTrue("异常消息应包含争议期未结束", e.getMessage().contains("Dispute period has not ended"));
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
        assertEquals("惩罚金应为发起方余额的 10%", 80L, penalty);
        assertTrue("惩罚金应大于 0", penalty > 0);

        // 测试 nonce 不更高时惩罚金为 0
        ChannelUpdate sameNonceUpdate = createFullySignedUpdate(1L, 700L, 300L);
        long zeroPenalty = disputeResolution.calculatePenalty(dispute, sameNonceUpdate);
        assertEquals("nonce 不更高时惩罚金应为 0", 0L, zeroPenalty);

        // 测试更低 nonce 时惩罚金为 0
        ChannelUpdate lowerNonceUpdate = createFullySignedUpdate(0L, 700L, 300L);
        long lowerPenalty = disputeResolution.calculatePenalty(dispute, lowerNonceUpdate);
        assertEquals("nonce 更低时惩罚金应为 0", 0L, lowerPenalty);

        // 测试 null 参数返回 0
        assertEquals("dispute 为 null 时应返回 0", 0L, disputeResolution.calculatePenalty(null, challengingUpdate));
        assertEquals("challengingUpdate 为 null 时应返回 0", 0L, disputeResolution.calculatePenalty(dispute, null));
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
        assertEquals("初始争议状态应为 ACTIVE", DisputeRecord.DisputeState.ACTIVE, initiated.getState());
        assertEquals("初始 nonce 应为 1", 1L, initiated.getLatestUpdate().getNonce());

        // 步骤2：挑战争议（提交 nonce=2 的新状态，余额 700/300）
        ChannelUpdate newUpdate = createFullySignedUpdate(2L, 700L, 300L);
        long challengeBlock = START_BLOCK + 30;
        DisputeRecord challenged = disputeResolution.challengeDispute(
                CHANNEL_ID, newUpdate,
                dummyPubkey, dummySig,
                challengeBlock
        );
        assertEquals("挑战后状态应为 CHALLENGED", DisputeRecord.DisputeState.CHALLENGED, challenged.getState());
        assertEquals("挑战后 nonce 应为 2", 2L, challenged.getLatestUpdate().getNonce());
        assertEquals("挑战后余额1 应为 700", 700L, challenged.getLatestUpdate().getBalance1());
        assertTrue("挑战后应有惩罚金", challenged.getPenaltyAmount() > 0);

        // 步骤3：争议期结束后结算
        long settleBlock = START_BLOCK + DISPUTE_PERIOD + 1;
        DisputeSettlement settlement = disputeResolution.settleDispute(CHANNEL_ID, settleBlock);

        // 验证最终结算结果
        assertNotNull("结算结果不应为 null", settlement);
        assertEquals("最终余额1 应为 700 减惩罚金",
                700L - challenged.getPenaltyAmount(), settlement.getFinalBalance1());
        assertEquals("最终余额2 应为 300", 300L, settlement.getFinalBalance2());
        assertTrue("惩罚金应大于 0", settlement.getPenaltyAmount() > 0);
        assertFalse("获胜方不应为平局", DisputeSettlement.WINNER_DRAW.equals(settlement.getWinner()));

        // 验证争议状态已结算
        DisputeRecord finalRecord = disputeResolution.getDisputeStatus(CHANNEL_ID);
        assertEquals("最终争议状态应为 SETTLED", DisputeRecord.DisputeState.SETTLED, finalRecord.getState());
    }
}

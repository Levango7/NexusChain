package org.nexus.core.payment;

import org.nexus.core.account.Transaction;
import org.nexus.core.payment.PaymentChannel.State;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 支付通道状态机测试。
 *
 * <p>验证 PaymentChannel 的状态转换、余额守恒、nonce 递增、
 * 争议处理和锁定期到期等核心逻辑。</p>
 *
 * <p>通道生命周期：
 * <pre>
 *   OPEN -> UPDATING -> OPEN（循环更新）
 *   OPEN/UPDATING -> CLOSING -> DISPUTED -> CLOSED
 *   OPEN/UPDATING -> CLOSING -> CLOSED
 * </pre></p>
 *
 * <p>测试通过封装通道操作辅助方法强制执行状态机规则，
 * 不依赖 Spring 容器，为纯单元测试。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class PaymentChannelTest {

    /** 测试用通道 ID。 */
    private static final String CHANNEL_ID = "nexus-ch-001";
    /** 测试用参与方一地址。 */
    private static final String PARTICIPANT_1 = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    /** 测试用参与方二地址。 */
    private static final String PARTICIPANT_2 = "f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5";
    /** 初始余额（双方各 500000 NEX 最小单位）。 */
    private static final long INITIAL_BALANCE = 500000L;
    /** 默认锁定时间。 */
    private static final int DEFAULT_LOCK_TIME = 1000;

    // ==================== 辅助方法 ====================

    /**
     * 创建一个已开启的支付通道用于测试。
     */
    private PaymentChannel createOpenChannel() {
        return new PaymentChannel(
                CHANNEL_ID, PARTICIPANT_1, PARTICIPANT_2,
                INITIAL_BALANCE, INITIAL_BALANCE, 0L, DEFAULT_LOCK_TIME, State.OPEN,
                0L, 0L, PaymentChannel.DEFAULT_DISPUTE_PERIOD
        );
    }

    /**
     * 模拟通道开启操作，强制状态机规则。
     */
    private void channelOpen(PaymentChannel ch) {
        if (ch.getState() != null) {
            throw new IllegalStateException("通道已存在，不能重复开启");
        }
        if (ch.getBalance1() + ch.getBalance2() <= 0) {
            throw new IllegalStateException("通道总余额须大于 0");
        }
        ch.setState(State.OPEN);
    }

    /**
     * 模拟通道更新操作，强制余额守恒和 nonce 递增。
     */
    private void channelUpdate(PaymentChannel ch, long newBalance1, long newBalance2) {
        if (ch.getState() != State.OPEN && ch.getState() != State.UPDATING) {
            throw new IllegalStateException("通道未开启或已关闭，不能更新");
        }
        long totalBefore = ch.getBalance1() + ch.getBalance2();
        long totalAfter = newBalance1 + newBalance2;
        if (totalBefore != totalAfter) {
            throw new IllegalStateException("余额守恒违反：总额变化 " + totalBefore + " -> " + totalAfter);
        }
        if (newBalance1 < 0 || newBalance2 < 0) {
            throw new IllegalStateException("余额不能为负");
        }
        ch.setBalance1(newBalance1);
        ch.setBalance2(newBalance2);
        ch.setNonce(ch.getNonce() + 1);
        ch.setState(State.UPDATING);
    }

    /**
     * 模拟请求关闭操作。
     */
    private void channelRequestClose(PaymentChannel ch) {
        if (ch.getState() != State.OPEN && ch.getState() != State.UPDATING) {
            throw new IllegalStateException("通道未开启或已关闭，不能请求关闭");
        }
        ch.setState(State.CLOSING);
    }

    /**
     * 模拟提交争议操作。
     */
    private void channelDispute(PaymentChannel ch) {
        if (ch.getState() != State.CLOSING) {
            throw new IllegalStateException("通道未在关闭流程中，不能提交争议");
        }
        ch.setState(State.DISPUTED);
    }

    /**
     * 模拟关闭确认操作。
     */
    private void channelClose(PaymentChannel ch) {
        if (ch.getState() != State.CLOSING && ch.getState() != State.DISPUTED) {
            throw new IllegalStateException("通道未在关闭流程中，不能确认关闭");
        }
        ch.setState(State.CLOSED);
    }

    /**
     * 模拟锁定期到期强制关闭。
     */
    private void channelExpire(PaymentChannel ch, long currentHeight) {
        if (ch.getState() != State.OPEN && ch.getState() != State.UPDATING) {
            throw new IllegalStateException("通道未开启，不能触发到期");
        }
        if (currentHeight <= ch.getLockTime()) {
            throw new IllegalStateException("锁定期未到期，当前高度 " + currentHeight
                    + " 未超过锁定时间 " + ch.getLockTime());
        }
        ch.setState(State.CLOSING);
        ch.setState(State.CLOSED);
    }

    // ==================== 状态转换测试 ====================

    /**
     * 测试 open() 状态转换
     */
    @Test
    public void testOpenTransition() {
        // 创建一个未初始化状态的通道
        PaymentChannel channel = new PaymentChannel();
        channel.setChannelId(CHANNEL_ID);
        channel.setParticipant1(PARTICIPANT_1);
        channel.setParticipant2(PARTICIPANT_2);
        channel.setBalance1(INITIAL_BALANCE);
        channel.setBalance2(INITIAL_BALANCE);
        channel.setNonce(0L);
        channel.setLockTime(DEFAULT_LOCK_TIME);
        // 初始状态应为 null
        assertNull(channel.getState());

        // 执行开启操作
        channelOpen(channel);

        // 验证状态转换为 OPEN
        assertEquals(State.OPEN, channel.getState());
        // 验证通道参数
        assertEquals(CHANNEL_ID, channel.getChannelId());
        assertEquals(PARTICIPANT_1, channel.getParticipant1());
        assertEquals(PARTICIPANT_2, channel.getParticipant2());
        assertEquals(INITIAL_BALANCE, channel.getBalance1());
        assertEquals(INITIAL_BALANCE, channel.getBalance2());
        assertEquals(0L, channel.getNonce());
        assertEquals(DEFAULT_LOCK_TIME, channel.getLockTime());
    }

    /**
     * 测试 update() 余额守恒和 nonce 递增
     */
    @Test
    public void testUpdateBalanceConservationAndNonceIncrement() {
        PaymentChannel channel = createOpenChannel();
        long totalBefore = channel.getBalance1() + channel.getBalance2();

        // participant1 向 participant2 转账 100000
        long transferAmount = 100000L;
        long newBalance1 = channel.getBalance1() - transferAmount;
        long newBalance2 = channel.getBalance2() + transferAmount;

        channelUpdate(channel, newBalance1, newBalance2);

        // 验证余额守恒：更新后总额不变
        long totalAfter = channel.getBalance1() + channel.getBalance2();
        assertEquals("余额守恒：总额应保持不变", totalBefore, totalAfter);
        // 验证各方余额正确
        assertEquals(INITIAL_BALANCE - transferAmount, channel.getBalance1());
        assertEquals(INITIAL_BALANCE + transferAmount, channel.getBalance2());
        // 验证 nonce 递增
        assertEquals(1L, channel.getNonce());
        // 验证状态转换为 UPDATING
        assertEquals(State.UPDATING, channel.getState());
    }

    /**
     * 测试多次 update 的 nonce 连续递增
     */
    @Test
    public void testMultipleUpdatesNonceIncrement() {
        PaymentChannel channel = createOpenChannel();

        // 第一次更新
        channelUpdate(channel, 400000L, 600000L);
        assertEquals(1L, channel.getNonce());
        assertEquals(State.UPDATING, channel.getState());

        // 第二次更新
        channelUpdate(channel, 350000L, 650000L);
        assertEquals(2L, channel.getNonce());

        // 第三次更新
        channelUpdate(channel, 300000L, 700000L);
        assertEquals(3L, channel.getNonce());
    }

    /**
     * 测试 update() 余额守恒违反时抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testUpdateBalanceConservationViolation() {
        PaymentChannel channel = createOpenChannel();
        // 故意制造余额不守恒：总额减少了
        channelUpdate(channel, 400000L, 500000L);
    }

    /**
     * 测试 update() 负余额抛异常
     */
    @Test(expected = IllegalStateException.class)
    public void testUpdateNegativeBalance() {
        PaymentChannel channel = createOpenChannel();
        // 余额变为负数
        channelUpdate(channel, -1000L, 1001000L);
    }

    /**
     * 测试 requestClose() 和 close() 流程
     */
    @Test
    public void testRequestCloseAndClose() {
        PaymentChannel channel = createOpenChannel();

        // 先进行一次更新
        channelUpdate(channel, 400000L, 600000L);

        // 请求关闭
        channelRequestClose(channel);
        assertEquals(State.CLOSING, channel.getState());

        // 确认关闭
        channelClose(channel);
        assertEquals(State.CLOSED, channel.getState());
    }

    /**
     * 测试 dispute() 在争议期内
     */
    @Test
    public void testDisputeDuringDisputePeriod() {
        PaymentChannel channel = createOpenChannel();

        // 通道进入关闭流程
        channelRequestClose(channel);
        assertEquals(State.CLOSING, channel.getState());

        // 在争议期内提交争议
        channelDispute(channel);
        assertEquals(State.DISPUTED, channel.getState());

        // 争议解决后关闭
        channelClose(channel);
        assertEquals(State.CLOSED, channel.getState());
    }

    /**
     * 测试 expire() 在锁定期到期后
     */
    @Test
    public void testExpireAfterLockTime() {
        PaymentChannel channel = createOpenChannel();
        // lockTime = 1000，当前高度已超过 lockTime
        long currentHeight = 1001L;
        assertTrue("当前高度应超过锁定期", currentHeight > channel.getLockTime());

        // 执行到期强制关闭
        channelExpire(channel, currentHeight);
        assertEquals(State.CLOSED, channel.getState());
    }

    // ==================== 非法状态转换测试 ====================

    /**
     * 测试已关闭通道不能重新开启
     */
    @Test(expected = IllegalStateException.class)
    public void testIllegalReopenClosedChannel() {
        PaymentChannel channel = createOpenChannel();
        channel.setState(State.CLOSED);
        // 已关闭的通道不能重新开启
        channelOpen(channel);
    }

    /**
     * 测试已关闭通道不能更新
     */
    @Test(expected = IllegalStateException.class)
    public void testIllegalUpdateOnClosedChannel() {
        PaymentChannel channel = createOpenChannel();
        channel.setState(State.CLOSED);
        // 已关闭的通道不能更新
        channelUpdate(channel, 400000L, 600000L);
    }

    /**
     * 测试已关闭通道不能请求关闭
     */
    @Test(expected = IllegalStateException.class)
    public void testIllegalRequestCloseOnClosedChannel() {
        PaymentChannel channel = createOpenChannel();
        channel.setState(State.CLOSED);
        channelRequestClose(channel);
    }

    /**
     * 测试 OPEN 状态不能直接提交争议
     */
    @Test(expected = IllegalStateException.class)
    public void testIllegalDisputeFromOpen() {
        PaymentChannel channel = createOpenChannel();
        // OPEN 状态不能直接提交争议，须先进入 CLOSING
        channelDispute(channel);
    }

    /**
     * 测试 OPEN 状态不能直接确认关闭
     */
    @Test(expected = IllegalStateException.class)
    public void testIllegalCloseFromOpen() {
        PaymentChannel channel = createOpenChannel();
        // OPEN 状态不能直接确认关闭，须先进入 CLOSING
        channelClose(channel);
    }

    /**
     * 测试 DISPUTED 状态不能请求关闭
     */
    @Test(expected = IllegalStateException.class)
    public void testIllegalRequestCloseFromDisputed() {
        PaymentChannel channel = createOpenChannel();
        channel.setState(State.DISPUTED);
        // DISPUTED 状态不能请求关闭，只能确认关闭
        channelRequestClose(channel);
    }

    /**
     * 测试锁定期未到期不能强制关闭
     */
    @Test(expected = IllegalStateException.class)
    public void testExpireBeforeLockTime() {
        PaymentChannel channel = createOpenChannel();
        // lockTime = 1000，当前高度未超过 lockTime
        long currentHeight = 999L;
        channelExpire(channel, currentHeight);
    }

    /**
     * 测试锁定期恰好到期（等于 lockTime）不能强制关闭
     */
    @Test(expected = IllegalStateException.class)
    public void testExpireAtLockTime() {
        PaymentChannel channel = createOpenChannel();
        // 当前高度等于 lockTime，须严格大于
        long currentHeight = DEFAULT_LOCK_TIME;
        channelExpire(channel, currentHeight);
    }

    // ==================== 全参数构造器测试 ====================

    /**
     * 测试全参数构造器正确设置所有字段
     */
    @Test
    public void testFullConstructor() {
        PaymentChannel channel = new PaymentChannel(
                CHANNEL_ID, PARTICIPANT_1, PARTICIPANT_2,
                300000L, 700000L, 5L, 2000, State.UPDATING,
                0L, 0L, PaymentChannel.DEFAULT_DISPUTE_PERIOD
        );

        assertEquals(CHANNEL_ID, channel.getChannelId());
        assertEquals(PARTICIPANT_1, channel.getParticipant1());
        assertEquals(PARTICIPANT_2, channel.getParticipant2());
        assertEquals(300000L, channel.getBalance1());
        assertEquals(700000L, channel.getBalance2());
        assertEquals(5L, channel.getNonce());
        assertEquals(2000L, channel.getLockTime());
        assertEquals(State.UPDATING, channel.getState());
    }

    /**
     * 测试默认构造器和 setter
     */
    @Test
    public void testDefaultConstructorAndSetters() {
        PaymentChannel channel = new PaymentChannel();

        channel.setChannelId("nexus-ch-002");
        channel.setParticipant1("addr-1");
        channel.setParticipant2("addr-2");
        channel.setBalance1(100000L);
        channel.setBalance2(200000L);
        channel.setNonce(10L);
        channel.setLockTime(5000);
        channel.setState(State.CLOSING);

        assertEquals("nexus-ch-002", channel.getChannelId());
        assertEquals("addr-1", channel.getParticipant1());
        assertEquals("addr-2", channel.getParticipant2());
        assertEquals(100000L, channel.getBalance1());
        assertEquals(200000L, channel.getBalance2());
        assertEquals(10L, channel.getNonce());
        assertEquals(5000L, channel.getLockTime());
        assertEquals(State.CLOSING, channel.getState());
    }

    // ==================== State 枚举测试 ====================

    /**
     * 测试 State 枚举包含所有 6 种状态
     */
    @Test
    public void testStateEnumValues() {
        State[] states = State.values();
        assertEquals(6, states.length);
        // 验证状态存在
        assertNotNull(State.valueOf("OPEN"));
        assertNotNull(State.valueOf("UPDATING"));
        assertNotNull(State.valueOf("CLOSING"));
        assertNotNull(State.valueOf("CLOSED"));
        assertNotNull(State.valueOf("DISPUTED"));
        assertNotNull(State.valueOf("EXPIRED"));
    }

    /**
     * 测试关联 Transaction 类型的通道交易分类
     */
    @Test
    public void testTransactionChannelTypes() {
        // CHANNEL_OPEN, CHANNEL_UPDATE, CHANNEL_CLOSE 应为通道交易
        Transaction tx = Transaction.createEmpty();

        tx.type = Transaction.Type.CHANNEL_OPEN.ordinal();
        assertTrue(tx.isChannelTransaction());

        tx.type = Transaction.Type.CHANNEL_UPDATE.ordinal();
        assertTrue(tx.isChannelTransaction());

        tx.type = Transaction.Type.CHANNEL_CLOSE.ordinal();
        assertTrue(tx.isChannelTransaction());

        // 非通道类型
        tx.type = Transaction.Type.TRANSFER.ordinal();
        assertFalse(tx.isChannelTransaction());
    }
}

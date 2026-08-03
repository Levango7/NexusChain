package org.nexus.core.payment;

import org.nexus.keystore.util.JsonUtils;

/**
 * 支付通道模型类。
 *
 * <p>表示 NEX 网络中两个参与者之间的双向支付通道状态。
 * 通道生命周期为：{@link State#OPEN} -> {@link State#UPDATING} ->
 * {@link State#OPEN} -> {@link State#CLOSING} -> {@link State#CLOSED}，
 * 争议期间可能进入 {@link State#DISPUTED} 状态，
 * 锁定期到期可进入 {@link State#EXPIRED} 状态。</p>
 *
 * <p>通道通过 {@code CHANNEL_OPEN} 交易开启，通过 {@code CHANNEL_UPDATE}
 * 交易进行链下状态更新，通过 {@code CHANNEL_CLOSE} 交易进行最终结算关闭。
 * 在争议期内，任一方可发起争议进入 {@link State#DISPUTED} 状态，
 * 争议解决后方可关闭通道。</p>
 *
 * <p>合法状态转换图：
 * <pre>
 *   null ---> OPEN ---> UPDATING ---> OPEN
 *          |     |
 *          |     +---> CLOSING ---> CLOSED
 *          |     |          |
 *          |     |          +---> DISPUTED ---> CLOSED
 *          |     |
 *          |     +---> EXPIRED
 * </pre></p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class PaymentChannel {

    /**
     * 支付通道状态枚举。
     */
    public enum State {
        /** 通道已开启，可正常进行链下更新。 */
        OPEN,
        /** 通道正在更新中（链下状态提交上链）。 */
        UPDATING,
        /** 通道正在关闭，进入争议期。 */
        CLOSING,
        /** 通道已关闭，结算完成。 */
        CLOSED,
        /** 通道处于争议状态，需要仲裁。 */
        DISPUTED,
        /** 通道已过期，锁定期到期后自动进入。 */
        EXPIRED
    }

    /** 默认争议期（区块数）。 */
    public static final int DEFAULT_DISPUTE_PERIOD = 100;

    /** 通道唯一标识符。 */
    private String channelId;

    /** 参与方一的地址（公钥哈希十六进制字符串）。 */
    private String participant1;

    /** 参与方二的地址（公钥哈希十六进制字符串）。 */
    private String participant2;

    /** 参与方一在通道中的余额（单位：NEX 最小单位）。 */
    private long balance1;

    /** 参与方二在通道中的余额（单位：NEX 最小单位）。 */
    private long balance2;

    /** 通道状态更新计数器，防止重放攻击。 */
    private long nonce;

    /** 通道锁定时间（区块高度），到期后可强制关闭。 */
    private int lockTime;

    /** 通道当前状态。 */
    private State state;

    /** 通道开启时的区块高度。 */
    private long openBlockHeight;

    /** 通道请求关闭时的区块高度。 */
    private long closeBlockHeight;

    /** 争议期长度（区块数），从 closeBlockHeight 开始计算。 */
    private int disputePeriod;

    /**
     * 默认构造函数。
     */
    public PaymentChannel() {
        this.disputePeriod = DEFAULT_DISPUTE_PERIOD;
    }

    /**
     * 全参数构造函数。
     *
     * @param channelId        通道唯一标识符
     * @param participant1     参与方一地址
     * @param participant2     参与方二地址
     * @param balance1         参与方一余额
     * @param balance2         参与方二余额
     * @param nonce            状态更新计数器
     * @param lockTime         锁定时间（区块高度）
     * @param state            通道状态
     * @param openBlockHeight  开启区块高度
     * @param closeBlockHeight 关闭区块高度
     * @param disputePeriod    争议期长度（区块数）
     */
    public PaymentChannel(String channelId, String participant1, String participant2,
                          long balance1, long balance2, long nonce, int lockTime, State state,
                          long openBlockHeight, long closeBlockHeight, int disputePeriod) {
        this.channelId = channelId;
        this.participant1 = participant1;
        this.participant2 = participant2;
        this.balance1 = balance1;
        this.balance2 = balance2;
        this.nonce = nonce;
        this.lockTime = lockTime;
        this.state = state;
        this.openBlockHeight = openBlockHeight;
        this.closeBlockHeight = closeBlockHeight;
        this.disputePeriod = disputePeriod;
    }

    // ==================== Getters and Setters ====================

    /**
     * 获取通道唯一标识符。
     * @return 通道 ID
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * 设置通道唯一标识符。
     * @param channelId 通道 ID
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * 获取参与方一地址。
     * @return 参与方一地址
     */
    public String getParticipant1() {
        return participant1;
    }

    /**
     * 设置参与方一地址。
     * @param participant1 参与方一地址
     */
    public void setParticipant1(String participant1) {
        this.participant1 = participant1;
    }

    /**
     * 获取参与方二地址。
     * @return 参与方二地址
     */
    public String getParticipant2() {
        return participant2;
    }

    /**
     * 设置参与方二地址。
     * @param participant2 参与方二地址
     */
    public void setParticipant2(String participant2) {
        this.participant2 = participant2;
    }

    /**
     * 获取参与方一余额。
     * @return 参与方一余额
     */
    public long getBalance1() {
        return balance1;
    }

    /**
     * 设置参与方一余额。
     * @param balance1 参与方一余额
     */
    public void setBalance1(long balance1) {
        this.balance1 = balance1;
    }

    /**
     * 获取参与方二余额。
     * @return 参与方二余额
     */
    public long getBalance2() {
        return balance2;
    }

    /**
     * 设置参与方二余额。
     * @param balance2 参与方二余额
     */
    public void setBalance2(long balance2) {
        this.balance2 = balance2;
    }

    /**
     * 获取状态更新计数器。
     * @return nonce 值
     */
    public long getNonce() {
        return nonce;
    }

    /**
     * 设置状态更新计数器。
     * @param nonce nonce 值
     */
    public void setNonce(long nonce) {
        this.nonce = nonce;
    }

    /**
     * 获取通道锁定时间。
     * @return 锁定时间（区块高度）
     */
    public int getLockTime() {
        return lockTime;
    }

    /**
     * 设置通道锁定时间。
     * @param lockTime 锁定时间（区块高度）
     */
    public void setLockTime(int lockTime) {
        this.lockTime = lockTime;
    }

    /**
     * 获取通道当前状态。
     * @return 通道状态
     */
    public State getState() {
        return state;
    }

    /**
     * 设置通道当前状态。
     * @param state 通道状态
     */
    public void setState(State state) {
        this.state = state;
    }

    /**
     * 获取通道开启时的区块高度。
     * @return 开启区块高度
     */
    public long getOpenBlockHeight() {
        return openBlockHeight;
    }

    /**
     * 设置通道开启时的区块高度。
     * @param openBlockHeight 开启区块高度
     */
    public void setOpenBlockHeight(long openBlockHeight) {
        this.openBlockHeight = openBlockHeight;
    }

    /**
     * 获取通道请求关闭时的区块高度。
     * @return 关闭区块高度
     */
    public long getCloseBlockHeight() {
        return closeBlockHeight;
    }

    /**
     * 设置通道请求关闭时的区块高度。
     * @param closeBlockHeight 关闭区块高度
     */
    public void setCloseBlockHeight(long closeBlockHeight) {
        this.closeBlockHeight = closeBlockHeight;
    }

    /**
     * 获取争议期长度。
     * @return 争议期长度（区块数）
     */
    public int getDisputePeriod() {
        return disputePeriod;
    }

    /**
     * 设置争议期长度。
     * @param disputePeriod 争议期长度（区块数）
     */
    public void setDisputePeriod(int disputePeriod) {
        this.disputePeriod = disputePeriod;
    }

    // ==================== State Transition Methods ====================

    /**
     * 开启支付通道。
     *
     * <p>状态转换：{@code null} -> {@link State#OPEN}。
     * 记录开启时的区块高度。</p>
     *
     * @param blockHeight 当前区块高度
     * @return 新状态 {@link State#OPEN}
     * @throws IllegalStateException 如果通道当前状态不为 null
     */
    public State open(long blockHeight) {
        if (this.state != null) {
            throw new IllegalStateException(
                    "Cannot open channel: channel is already in state " + this.state);
        }
        if (disputePeriod <= 0) {
            this.disputePeriod = DEFAULT_DISPUTE_PERIOD;
        }
        this.openBlockHeight = blockHeight;
        this.state = State.OPEN;
        return this.state;
    }

    /**
     * 更新通道余额和 nonce。
     *
     * <p>状态转换：{@link State#OPEN} -> {@link State#UPDATING} -> {@link State#OPEN}。
     * 验证 nonce 递增（newNonce > 当前 nonce）和余额守恒
     * （newBalance1 + newBalance2 == balance1 + balance2）。</p>
     *
     * @param newBalance1 参与方一新余额
     * @param newBalance2 参与方二新余额
     * @param newNonce    新的 nonce 值，必须大于当前 nonce
     * @return 新状态 {@link State#OPEN}
     * @throws IllegalStateException    如果通道当前状态不为 OPEN
     * @throws IllegalArgumentException 如果 nonce 未递增或余额不守恒
     */
    public State update(long newBalance1, long newBalance2, long newNonce) {
        if (this.state != State.OPEN) {
            throw new IllegalStateException(
                    "Cannot update channel: expected OPEN, got " + this.state);
        }
        if (newNonce <= this.nonce) {
            throw new IllegalArgumentException(
                    "Nonce must increase: newNonce=" + newNonce + ", current=" + this.nonce);
        }
        long oldTotal = this.balance1 + this.balance2;
        long newTotal = newBalance1 + newBalance2;
        if (oldTotal != newTotal) {
            throw new IllegalArgumentException(
                    "Balance conservation violated: oldTotal=" + oldTotal + ", newTotal=" + newTotal);
        }
        if (newBalance1 < 0 || newBalance2 < 0) {
            throw new IllegalArgumentException(
                    "Balances must be non-negative: balance1=" + newBalance1 + ", balance2=" + newBalance2);
        }
        // Transition to intermediate UPDATING state
        this.state = State.UPDATING;
        // Apply updates
        this.balance1 = newBalance1;
        this.balance2 = newBalance2;
        this.nonce = newNonce;
        // Transition back to OPEN
        this.state = State.OPEN;
        return this.state;
    }

    /**
     * 请求关闭通道，进入争议期。
     *
     * <p>状态转换：{@link State#OPEN} -> {@link State#CLOSING}。
     * 记录请求关闭时的区块高度。</p>
     *
     * @param blockHeight 当前区块高度
     * @return 新状态 {@link State#CLOSING}
     * @throws IllegalStateException 如果通道当前状态不为 OPEN
     */
    public State requestClose(long blockHeight) {
        if (this.state != State.OPEN) {
            throw new IllegalStateException(
                    "Cannot request close: expected OPEN, got " + this.state);
        }
        this.closeBlockHeight = blockHeight;
        this.state = State.CLOSING;
        return this.state;
    }

    /**
     * 关闭通道，完成结算。
     *
     * <p>状态转换：{@link State#CLOSING} -> {@link State#CLOSED}。
     * 验证争议期已过（当前区块高度 >= closeBlockHeight + disputePeriod）。</p>
     *
     * @param currentBlockHeight 当前区块高度
     * @return 新状态 {@link State#CLOSED}
     * @throws IllegalStateException 如果通道当前状态不为 CLOSING 或争议期未过
     */
    public State close(long currentBlockHeight) {
        if (this.state != State.CLOSING) {
            throw new IllegalStateException(
                    "Cannot close channel: expected CLOSING, got " + this.state);
        }
        if (isInDisputePeriod(currentBlockHeight)) {
            throw new IllegalStateException(
                    "Cannot close channel: still in dispute period (current=" + currentBlockHeight
                            + ", disputeEnds=" + (closeBlockHeight + disputePeriod) + ")");
        }
        this.state = State.CLOSED;
        return this.state;
    }

    /**
     * 发起争议。
     *
     * <p>状态转换：{@link State#CLOSING} -> {@link State#DISPUTED}。
     * 必须在争议期内调用。</p>
     *
     * @return 新状态 {@link State#DISPUTED}
     * @throws IllegalStateException 如果通道当前状态不为 CLOSING
     */
    public State dispute() {
        if (this.state != State.CLOSING) {
            throw new IllegalStateException(
                    "Cannot dispute channel: expected CLOSING, got " + this.state);
        }
        this.state = State.DISPUTED;
        return this.state;
    }

    /**
     * 使通道过期。
     *
     * <p>状态转换：{@link State#OPEN} -> {@link State#EXPIRED}。
     * 验证锁定期已到期（lockTime > 0 且 currentBlockHeight >= lockTime）。</p>
     *
     * @param currentBlockHeight 当前区块高度
     * @return 新状态 {@link State#EXPIRED}
     * @throws IllegalStateException 如果通道当前状态不为 OPEN 或锁定期未到期
     */
    public State expire(long currentBlockHeight) {
        if (this.state != State.OPEN) {
            throw new IllegalStateException(
                    "Cannot expire channel: expected OPEN, got " + this.state);
        }
        if (lockTime <= 0) {
            throw new IllegalStateException(
                    "Cannot expire channel: lockTime is not set (lockTime=" + lockTime + ")");
        }
        if (currentBlockHeight < lockTime) {
            throw new IllegalStateException(
                    "Cannot expire channel: lockTime not yet reached (current=" + currentBlockHeight
                            + ", lockTime=" + lockTime + ")");
        }
        this.state = State.EXPIRED;
        return this.state;
    }

    // ==================== Validation Methods ====================

    /**
     * 校验状态转换是否合法。
     *
     * <p>合法转换：
     * <ul>
     *   <li>{@code null} -> {@link State#OPEN}</li>
     *   <li>{@link State#OPEN} -> {@link State#UPDATING}</li>
     *   <li>{@link State#UPDATING} -> {@link State#OPEN}</li>
     *   <li>{@link State#OPEN} -> {@link State#CLOSING}</li>
     *   <li>{@link State#CLOSING} -> {@link State#CLOSED}</li>
     *   <li>{@link State#CLOSING} -> {@link State#DISPUTED}</li>
     *   <li>{@link State#OPEN} -> {@link State#EXPIRED}</li>
     *   <li>{@link State#DISPUTED} -> {@link State#CLOSED}</li>
     * </ul></p>
     *
     * @param from 源状态，可为 null
     * @param to   目标状态
     * @return true 如果转换合法，false 否则
     */
    public static boolean isValidStateTransition(State from, State to) {
        if (from == null) {
            return to == State.OPEN;
        }
        switch (from) {
            case OPEN:
                return to == State.UPDATING || to == State.CLOSING || to == State.EXPIRED;
            case UPDATING:
                return to == State.OPEN;
            case CLOSING:
                return to == State.CLOSED || to == State.DISPUTED;
            case DISPUTED:
                return to == State.CLOSED;
            case CLOSED:
            case EXPIRED:
                return false;
            default:
                return false;
        }
    }

    /**
     * 检查通道是否处于锁定状态。
     *
     * <p>通道在 {@link State#UPDATING}、{@link State#CLOSING} 或
     * {@link State#DISPUTED} 状态时被视为锁定，无法进行正常的
     * 链下更新操作。</p>
     *
     * @return true 如果通道处于锁定状态
     */
    public boolean isLocked() {
        return state == State.UPDATING || state == State.CLOSING || state == State.DISPUTED;
    }

    /**
     * 检查通道是否处于争议期内。
     *
     * <p>争议期从 closeBlockHeight 开始，持续 disputePeriod 个区块。
     * 当 closeBlockHeight > 0 且 currentBlockHeight < closeBlockHeight + disputePeriod
     * 时，通道处于争议期内。</p>
     *
     * @param currentBlockHeight 当前区块高度
     * @return true 如果通道处于争议期内
     */
    public boolean isInDisputePeriod(long currentBlockHeight) {
        if (closeBlockHeight <= 0 || disputePeriod <= 0) {
            return false;
        }
        return currentBlockHeight < closeBlockHeight + disputePeriod;
    }

    /**
     * 获取通道总余额（balance1 + balance2）。
     *
     * @return 通道总余额
     */
    public long getTotalBalance() {
        return balance1 + balance2;
    }

    // ==================== Serialization ====================

    /**
     * 将通道状态序列化为 JSON 字符串。
     *
     * <p>使用 fastjson 进行序列化，包含所有字段。</p>
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return JsonUtils.toJson(this);
    }

    /**
     * 从 JSON 字符串反序列化为 PaymentChannel 对象。
     *
     * <p>使用 fastjson 进行反序列化。</p>
     *
     * @param json JSON 字符串
     * @return PaymentChannel 对象
     */
    public static PaymentChannel fromJson(String json) {
        return JsonUtils.fromJson(json, PaymentChannel.class);
    }

    @Override
    public String toString() {
        return "PaymentChannel{" +
                "channelId='" + channelId + '\'' +
                ", participant1='" + participant1 + '\'' +
                ", participant2='" + participant2 + '\'' +
                ", balance1=" + balance1 +
                ", balance2=" + balance2 +
                ", nonce=" + nonce +
                ", lockTime=" + lockTime +
                ", state=" + state +
                ", openBlockHeight=" + openBlockHeight +
                ", closeBlockHeight=" + closeBlockHeight +
                ", disputePeriod=" + disputePeriod +
                '}';
    }
}

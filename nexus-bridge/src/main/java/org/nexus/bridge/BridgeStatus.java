package org.nexus.bridge;

/**
 * 桥运行状态信息，包含当前状态和限额使用情况。
 *
 * <p>通过 {@link BridgeService#getStatus()} 获取，用于监控桥的
 * 运行状况和限额余量。</p>
 *
 * @since 1.0.0
 */
public class BridgeStatus {

    /** 当前桥状态。 */
    private BridgeState state;

    /** 今日已使用的跨链流出额度（NEX 最小单位）。 */
    private long dailyUsed;

    /** 日限额总量（NEX 最小单位）。 */
    private long dailyLimit;

    /** 今日已完成跨链交易数。 */
    private int dailyTxCount;

    /** 当前待确认的跨链交易数。 */
    private int pendingTxCount;

    /** 活跃验证者数量。 */
    private int activeValidatorCount;

    /** 签名阈值。 */
    private int signatureThreshold;

    /**
     * 默认构造函数。
     */
    public BridgeStatus() {
    }

    public BridgeState getState() {
        return state;
    }

    public void setState(BridgeState state) {
        this.state = state;
    }

    public long getDailyUsed() {
        return dailyUsed;
    }

    public void setDailyUsed(long dailyUsed) {
        this.dailyUsed = dailyUsed;
    }

    public long getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(long dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public int getDailyTxCount() {
        return dailyTxCount;
    }

    public void setDailyTxCount(int dailyTxCount) {
        this.dailyTxCount = dailyTxCount;
    }

    public int getPendingTxCount() {
        return pendingTxCount;
    }

    public void setPendingTxCount(int pendingTxCount) {
        this.pendingTxCount = pendingTxCount;
    }

    public int getActiveValidatorCount() {
        return activeValidatorCount;
    }

    public void setActiveValidatorCount(int activeValidatorCount) {
        this.activeValidatorCount = activeValidatorCount;
    }

    public int getSignatureThreshold() {
        return signatureThreshold;
    }

    public void setSignatureThreshold(int signatureThreshold) {
        this.signatureThreshold = signatureThreshold;
    }

    /**
     * 获取今日剩余限额。
     *
     * @return 剩余额度（NEX 最小单位）
     */
    public long getDailyRemaining() {
        return Math.max(0, dailyLimit - dailyUsed);
    }

    @Override
    public String toString() {
        return "BridgeStatus{"
                + "state=" + state
                + ", dailyUsed=" + dailyUsed
                + ", dailyLimit=" + dailyLimit
                + ", dailyTxCount=" + dailyTxCount
                + ", pendingTxCount=" + pendingTxCount
                + ", activeValidatorCount=" + activeValidatorCount
                + ", signatureThreshold=" + signatureThreshold
                + '}';
    }
}

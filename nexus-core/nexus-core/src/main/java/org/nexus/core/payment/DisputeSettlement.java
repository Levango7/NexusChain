package org.nexus.core.payment;

import org.nexus.keystore.util.JsonUtils;

/**
 * 争议结算结果模型。
 *
 * <p>当争议期结束后，{@link DisputeResolution#settleDispute} 方法
 * 根据最高 nonce 的 {@link ChannelUpdate} 计算最终余额分配，
 * 并返回此结算结果。结算结果包含双方最终余额、惩罚金额、
 * 获胜方标识以及结算区块高度。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class DisputeSettlement {

    /** 获胜方为参与方一。 */
    public static final String WINNER_PARTICIPANT1 = "participant1";

    /** 获胜方为参与方二。 */
    public static final String WINNER_PARTICIPANT2 = "participant2";

    /** 平局（双方均无过错或双方均有过错）。 */
    public static final String WINNER_DRAW = "draw";

    /** 通道唯一标识符。 */
    private String channelId;

    /** 参与方一最终余额（NEX 最小单位）。 */
    private long finalBalance1;

    /** 参与方二最终余额（NEX 最小单位）。 */
    private long finalBalance2;

    /** 惩罚金额（NEX 最小单位），从过错方余额中扣除。 */
    private long penaltyAmount;

    /** 获胜方标识："participant1"、"participant2" 或 "draw"。 */
    private String winner;

    /** 结算时的区块高度。 */
    private long settledBlock;

    /**
     * 默认构造函数（用于 JSON 反序列化）。
     */
    public DisputeSettlement() {
    }

    /**
     * 全参数构造函数。
     *
     * @param channelId      通道 ID
     * @param finalBalance1   参与方一最终余额
     * @param finalBalance2   参与方二最终余额
     * @param penaltyAmount    惩罚金额
     * @param winner           获胜方标识
     * @param settledBlock     结算区块高度
     */
    public DisputeSettlement(String channelId, long finalBalance1, long finalBalance2,
                            long penaltyAmount, String winner, long settledBlock) {
        this.channelId = channelId;
        this.finalBalance1 = finalBalance1;
        this.finalBalance2 = finalBalance2;
        this.penaltyAmount = penaltyAmount;
        this.winner = winner;
        this.settledBlock = settledBlock;
    }

    // ==================== Getters and Setters ====================

    /**
     * 获取通道 ID。
     * @return 通道 ID
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * 设置通道 ID。
     * @param channelId 通道 ID
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * 获取参与方一最终余额。
     * @return 参与方一最终余额
     */
    public long getFinalBalance1() {
        return finalBalance1;
    }

    /**
     * 设置参与方一最终余额。
     * @param finalBalance1 参与方一最终余额
     */
    public void setFinalBalance1(long finalBalance1) {
        this.finalBalance1 = finalBalance1;
    }

    /**
     * 获取参与方二最终余额。
     * @return 参与方二最终余额
     */
    public long getFinalBalance2() {
        return finalBalance2;
    }

    /**
     * 设置参与方二最终余额。
     * @param finalBalance2 参与方二最终余额
     */
    public void setFinalBalance2(long finalBalance2) {
        this.finalBalance2 = finalBalance2;
    }

    /**
     * 获取惩罚金额。
     * @return 惩罚金额
     */
    public long getPenaltyAmount() {
        return penaltyAmount;
    }

    /**
     * 设置惩罚金额。
     * @param penaltyAmount 惩罚金额
     */
    public void setPenaltyAmount(long penaltyAmount) {
        this.penaltyAmount = penaltyAmount;
    }

    /**
     * 获取获胜方标识。
     * @return 获胜方标识
     */
    public String getWinner() {
        return winner;
    }

    /**
     * 设置获胜方标识。
     * @param winner 获胜方标识
     */
    public void setWinner(String winner) {
        this.winner = winner;
    }

    /**
     * 获取结算区块高度。
     * @return 结算区块高度
     */
    public long getSettledBlock() {
        return settledBlock;
    }

    /**
     * 设置结算区块高度。
     * @param settledBlock 结算区块高度
     */
    public void setSettledBlock(long settledBlock) {
        this.settledBlock = settledBlock;
    }

    // ==================== Serialization ====================

    /**
     * 将结算结果序列化为 JSON 字符串。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return JsonUtils.toJson(this);
    }

    /**
     * 从 JSON 字符串反序列化为 DisputeSettlement 对象。
     *
     * @param json JSON 字符串
     * @return DisputeSettlement 对象
     */
    public static DisputeSettlement fromJson(String json) {
        return JsonUtils.fromJson(json, DisputeSettlement.class);
    }

    @Override
    public String toString() {
        return "DisputeSettlement{" +
                "channelId='" + channelId + '\'' +
                ", finalBalance1=" + finalBalance1 +
                ", finalBalance2=" + finalBalance2 +
                ", penaltyAmount=" + penaltyAmount +
                ", winner='" + winner + '\'' +
                ", settledBlock=" + settledBlock +
                '}';
    }
}

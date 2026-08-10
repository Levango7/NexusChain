package org.nexus.core.payment;

import org.nexus.keystore.util.JsonUtils;

/**
 * 通道结算结果模型。
 *
 * <p>记录一次支付通道关闭结算的完整结果，比 {@link DisputeSettlement}
 * 包含更完整的信息。结算结果涵盖双方最终余额、惩罚金额、结算类型、
 * 获胜方标识、结算区块高度、资金分配交易哈希以及总分配金额。</p>
 *
 * <p>结算类型（{@link #settlementType}）取值：
 * <ul>
 *   <li>{@code COOPERATIVE} - 协作关闭，双方签名同意最终余额</li>
 *   <li>{@code DISPUTE} - 争议关闭，争议期过后按最高 nonce 的 update 结算</li>
 *   <li>{@code UNILATERAL} - 单方关闭，一方提交最终状态进入争议期</li>
 *   <li>{@code EXPIRED} - 强制过期关闭，通道锁定期到期后自动结算</li>
 * </ul></p>
 *
 * <p>获胜方标识（{@link #winner}）取值：
 * {@code participant1}、{@code participant2}、{@code draw} 或 {@code null}。</p>
 *
 * <p>该模型由 {@code ChannelSettlementService} 在通道关闭结算时创建，
 * 用于返回给调用方并记录结算的完整信息。</p>
 *
 * @author nexus-core
 * @since 1.0
 * @see DisputeSettlement
 */
public class ChannelSettlement {

    /** 结算类型：协作关闭。 */
    public static final String TYPE_COOPERATIVE = "COOPERATIVE";

    /** 结算类型：争议关闭。 */
    public static final String TYPE_DISPUTE = "DISPUTE";

    /** 结算类型：单方关闭。 */
    public static final String TYPE_UNILATERAL = "UNILATERAL";

    /** 结算类型：强制过期关闭。 */
    public static final String TYPE_EXPIRED = "EXPIRED";

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

    /** 惩罚金额（NEX 最小单位），从过错方余额中扣除，无惩罚时为 0。 */
    private long penaltyAmount;

    /** 结算类型：COOPERATIVE、DISPUTE、UNILATERAL、EXPIRED。 */
    private String settlementType;

    /** 获胜方标识：participant1、participant2、draw 或 null。 */
    private String winner;

    /** 结算时的区块高度。 */
    private long settledBlock;

    /** 分配给参与方一的 TRANSFER 交易哈希（十六进制字符串）。 */
    private String transferTxHash1;

    /** 分配给参与方二的 TRANSFER 交易哈希（十六进制字符串）。 */
    private String transferTxHash2;

    /** 总分配金额（finalBalance1 + finalBalance2，NEX 最小单位）。 */
    private long totalDistributed;

    /**
     * 默认构造函数（用于 JSON 反序列化）。
     */
    public ChannelSettlement() {
    }

    /**
     * 全参数构造函数。
     *
     * @param channelId        通道 ID
     * @param finalBalance1      参与方一最终余额
     * @param finalBalance2      参与方二最终余额
     * @param penaltyAmount       惩罚金额，无惩罚为 0
     * @param settlementType      结算类型
     * @param winner              获胜方标识，可为 null
     * @param settledBlock        结算区块高度
     * @param transferTxHash1      分配给参与方一交易哈希
     * @param transferTxHash2      分配给参与方二交易哈希
     * @param totalDistributed     总分配金额
     */
    public ChannelSettlement(String channelId, long finalBalance1, long finalBalance2,
                              long penaltyAmount, String settlementType, String winner,
                              long settledBlock, String transferTxHash1, String transferTxHash2,
                              long totalDistributed) {
        this.channelId = channelId;
        this.finalBalance1 = finalBalance1;
        this.finalBalance2 = finalBalance2;
        this.penaltyAmount = penaltyAmount;
        this.settlementType = settlementType;
        this.winner = winner;
        this.settledBlock = settledBlock;
        this.transferTxHash1 = transferTxHash1;
        this.transferTxHash2 = transferTxHash2;
        this.totalDistributed = totalDistributed;
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
     * @return 惩罚金额，无惩罚时为 0
     */
    public long getPenaltyAmount() {
        return penaltyAmount;
    }

    /**
     * 设置惩罚金额。
     * @param penaltyAmount 惩罚金额，无惩罚为 0
     */
    public void setPenaltyAmount(long penaltyAmount) {
        this.penaltyAmount = penaltyAmount;
    }

    /**
     * 获取结算类型。
     * @return 结算类型字符串
     */
    public String getSettlementType() {
        return settlementType;
    }

    /**
     * 设置结算类型。
     * @param settlementType 结算类型字符串
     */
    public void setSettlementType(String settlementType) {
        this.settlementType = settlementType;
    }

    /**
     * 获取获胜方标识。
     * @return 获胜方标识，可能为 null
     */
    public String getWinner() {
        return winner;
    }

    /**
     * 设置获胜方标识。
     * @param winner 获胜方标识，可为 null
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

    /**
     * 获取分配给参与方一交易哈希。
     * @return 交易哈希十六进制字符串
     */
    public String getTransferTxHash1() {
        return transferTxHash1;
    }

    /**
     * 设置分配给参与方一交易哈希。
     * @param transferTxHash1 交易哈希十六进制字符串
     */
    public void setTransferTxHash1(String transferTxHash1) {
        this.transferTxHash1 = transferTxHash1;
    }

    /**
     * 获取分配给参与方二交易哈希。
     * @return 交易哈希十六进制字符串
     */
    public String getTransferTxHash2() {
        return transferTxHash2;
    }

    /**
     * 设置分配给参与方二交易哈希。
     * @param transferTxHash2 交易哈希十六进制字符串
     */
    public void setTransferTxHash2(String transferTxHash2) {
        this.transferTxHash2 = transferTxHash2;
    }

    /**
     * 获取总分配金额。
     * @return 总分配金额（NEX 最小单位）
     */
    public long getTotalDistributed() {
        return totalDistributed;
    }

    /**
     * 设置总分配金额。
     * @param totalDistributed 总分配金额
     */
    public void setTotalDistributed(long totalDistributed) {
        this.totalDistributed = totalDistributed;
    }

    // ==================== Serialization ====================

    /**
     * 将结算结果序列化为 JSON 字符串。
     *
     * <p>使用 fastjson 进行序列化，包含所有字段。</p>
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return JsonUtils.toJson(this);
    }

    /**
     * 从 JSON 字符串反序列化为 ChannelSettlement 对象。
     *
     * @param json JSON 字符串
     * @return ChannelSettlement 对象
     */
    public static ChannelSettlement fromJson(String json) {
        return JsonUtils.fromJson(json, ChannelSettlement.class);
    }

    @Override
    public String toString() {
        return "ChannelSettlement{" +
                "channelId='" + channelId + '\'' +
                ", finalBalance1=" + finalBalance1 +
                ", finalBalance2=" + finalBalance2 +
                ", penaltyAmount=" + penaltyAmount +
                ", settlementType='" + settlementType + '\'' +
                ", winner='" + winner + '\'' +
                ", settledBlock=" + settledBlock +
                ", transferTxHash1='" + transferTxHash1 + '\'' +
                ", transferTxHash2='" + transferTxHash2 + '\'' +
                ", totalDistributed=" + totalDistributed +
                '}';
    }
}

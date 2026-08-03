package org.nexus.core.payment;

import org.nexus.keystore.util.JsonUtils;

/**
 * 争议记录模型。
 *
 * <p>记录一次链上争议关闭的完整状态信息，包括争议起止区块高度、
 * 当前最高 nonce 的 {@link ChannelUpdate}、发起方公钥、争议状态
 * 以及惩罚金额。争议记录由 {@link DisputeResolution} 组件创建和管理。</p>
 *
 * <p>争议状态转换流程：
 * <pre>
 *   null ---> ACTIVE ---> CHALLENGED ---> SETTLED
 *                   |
 *                   +---> EXPIRED (争议期结束无人挑战)
 * </pre></p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class DisputeRecord {

    /**
     * 争议状态枚举。
     */
    public enum DisputeState {
        /** 争议已发起，等待挑战。 */
        ACTIVE,
        /** 已收到挑战，更新了更高 nonce 的 update。 */
        CHALLENGED,
        /** 争议已结算，通道已关闭。 */
        SETTLED,
        /** 争议期已过，无人挑战，可自动结算。 */
        EXPIRED
    }

    /** 通道唯一标识符。 */
    private String channelId;

    /** 争议开始区块高度。 */
    private long startBlock;

    /** 争议结束区块高度（startBlock + disputePeriod）。 */
    private long endBlock;

    /** 当前最高 nonce 的 ChannelUpdate。 */
    private ChannelUpdate latestUpdate;

    /** 发起方公钥（十六进制字符串）。 */
    private String initiatorPubkey;

    /** 争议当前状态。 */
    private DisputeState state;

    /** 惩罚金额（NEX 最小单位）。 */
    private long penaltyAmount;

    /**
     * 默认构造函数（用于 JSON 反序列化）。
     */
    public DisputeRecord() {
    }

    /**
     * 全参数构造函数。
     *
     * @param channelId        通道 ID
     * @param startBlock        争议开始区块高度
     * @param endBlock          争议结束区块高度
     * @param latestUpdate      当前最高 nonce 的 update
     * @param initiatorPubkey   发起方公钥（十六进制）
     * @param state             争议状态
     * @param penaltyAmount     惩罚金额
     */
    public DisputeRecord(String channelId, long startBlock, long endBlock,
                         ChannelUpdate latestUpdate, String initiatorPubkey,
                         DisputeState state, long penaltyAmount) {
        this.channelId = channelId;
        this.startBlock = startBlock;
        this.endBlock = endBlock;
        this.latestUpdate = latestUpdate;
        this.initiatorPubkey = initiatorPubkey;
        this.state = state;
        this.penaltyAmount = penaltyAmount;
    }

    // ==================== Validation ====================

    /**
     * 检查当前是否处于争议期内。
     *
     * <p>争议期从 startBlock 到 endBlock。当 currentBlockHeight < endBlock
     * 时，视为处于争议期内。</p>
     *
     * @param currentBlockHeight 当前区块高度
     * @return true 如果在争议期内
     */
    public boolean isInDisputePeriod(long currentBlockHeight) {
        return currentBlockHeight < endBlock;
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
     * 获取争议开始区块高度。
     * @return 开始区块高度
     */
    public long getStartBlock() {
        return startBlock;
    }

    /**
     * 设置争议开始区块高度。
     * @param startBlock 开始区块高度
     */
    public void setStartBlock(long startBlock) {
        this.startBlock = startBlock;
    }

    /**
     * 获取争议结束区块高度。
     * @return 结束区块高度
     */
    public long getEndBlock() {
        return endBlock;
    }

    /**
     * 设置争议结束区块高度。
     * @param endBlock 结束区块高度
     */
    public void setEndBlock(long endBlock) {
        this.endBlock = endBlock;
    }

    /**
     * 获取当前最高 nonce 的 ChannelUpdate。
     * @return 最新 ChannelUpdate
     */
    public ChannelUpdate getLatestUpdate() {
        return latestUpdate;
    }

    /**
     * 设置当前最高 nonce 的 ChannelUpdate。
     * @param latestUpdate 最新 ChannelUpdate
     */
    public void setLatestUpdate(ChannelUpdate latestUpdate) {
        this.latestUpdate = latestUpdate;
    }

    /**
     * 获取发起方公钥。
     * @return 发起方公钥（十六进制字符串）
     */
    public String getInitiatorPubkey() {
        return initiatorPubkey;
    }

    /**
     * 设置发起方公钥。
     * @param initiatorPubkey 发起方公钥（十六进制字符串）
     */
    public void setInitiatorPubkey(String initiatorPubkey) {
        this.initiatorPubkey = initiatorPubkey;
    }

    /**
     * 获取争议状态。
     * @return 争议状态
     */
    public DisputeState getState() {
        return state;
    }

    /**
     * 设置争议状态。
     * @param state 争议状态
     */
    public void setState(DisputeState state) {
        this.state = state;
    }

    /**
     * 获取惩罚金额。
     * @return 惩罚金额（NEX 最小单位）
     */
    public long getPenaltyAmount() {
        return penaltyAmount;
    }

    /**
     * 设置惩罚金额。
     * @param penaltyAmount 惩罚金额（NEX 最小单位）
     */
    public void setPenaltyAmount(long penaltyAmount) {
        this.penaltyAmount = penaltyAmount;
    }

    // ==================== Serialization ====================

    /**
     * 将争议记录序列化为 JSON 字符串。
     *
     * <p>嵌套的 {@link ChannelUpdate} 会递归序列化。使用 fastjson。</p>
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return JsonUtils.toJson(this);
    }

    /**
     * 从 JSON 字符串反序列化为 DisputeRecord 对象。
     *
     * @param json JSON 字符串
     * @return DisputeRecord 对象
     */
    public static DisputeRecord fromJson(String json) {
        return JsonUtils.fromJson(json, DisputeRecord.class);
    }

    @Override
    public String toString() {
        return "DisputeRecord{" +
                "channelId='" + channelId + '\'' +
                ", startBlock=" + startBlock +
                ", endBlock=" + endBlock +
                ", latestUpdate=" + latestUpdate +
                ", initiatorPubkey='" + initiatorPubkey + '\'' +
                ", state=" + state +
                ", penaltyAmount=" + penaltyAmount +
                '}';
    }
}

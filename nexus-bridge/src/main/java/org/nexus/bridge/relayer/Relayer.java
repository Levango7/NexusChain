package org.nexus.bridge.relayer;

import java.math.BigDecimal;

/**
 * Relayer 实体。
 *
 * <p>描述参与跨链中继网络的节点信息。</p>
 *
 * @since 1.2
 */
public class Relayer {

    /** Relayer ID */
    private String relayerId;

    /** 节点地址（hex） */
    private String address;

    /** 质押金额 */
    private BigDecimal stake;

    /** 信誉分（0~100） */
    private double reputationScore;

    /** 状态 */
    private RelayerStatus status;

    public Relayer() {
    }

    public Relayer(String relayerId, String address, BigDecimal stake,
                   double reputationScore, RelayerStatus status) {
        this.relayerId = relayerId;
        this.address = address;
        this.stake = stake;
        this.reputationScore = reputationScore;
        this.status = status;
    }

    public String getRelayerId() {
        return relayerId;
    }

    public void setRelayerId(String relayerId) {
        this.relayerId = relayerId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public BigDecimal getStake() {
        return stake;
    }

    public void setStake(BigDecimal stake) {
        this.stake = stake;
    }

    public double getReputationScore() {
        return reputationScore;
    }

    public void setReputationScore(double reputationScore) {
        this.reputationScore = reputationScore;
    }

    public RelayerStatus getStatus() {
        return status;
    }

    public void setStatus(RelayerStatus status) {
        this.status = status;
    }
}
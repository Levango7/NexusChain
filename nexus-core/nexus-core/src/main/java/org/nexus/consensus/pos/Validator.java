package org.nexus.consensus.pos;

import java.math.BigDecimal;

/**
 * PoS 验证者实体。
 *
 * <p>描述参与权益质押共识的节点信息。</p>
 *
 * @since 1.2
 */
public class Validator {

    /** 验证者地址（hex） */
    private String address;

    /** 验证者公钥（hex） */
    private String publicKey;

    /** 质押金额 */
    private BigDecimal stakeAmount;

    /** 佣金率（0~1） */
    private double commissionRate;

    /** 验证者状态 */
    private ValidatorStatus status;

    public Validator() {
    }

    public Validator(String address, String publicKey, BigDecimal stakeAmount,
                     double commissionRate, ValidatorStatus status) {
        this.address = address;
        this.publicKey = publicKey;
        this.stakeAmount = stakeAmount;
        this.commissionRate = commissionRate;
        this.status = status;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public BigDecimal getStakeAmount() {
        return stakeAmount;
    }

    public void setStakeAmount(BigDecimal stakeAmount) {
        this.stakeAmount = stakeAmount;
    }

    public double getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(double commissionRate) {
        this.commissionRate = commissionRate;
    }

    public ValidatorStatus getStatus() {
        return status;
    }

    public void setStatus(ValidatorStatus status) {
        this.status = status;
    }
}
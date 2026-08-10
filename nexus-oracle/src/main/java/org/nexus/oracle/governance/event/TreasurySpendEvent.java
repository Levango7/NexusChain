package org.nexus.oracle.governance.event;

import org.nexus.oracle.governance.Proposal;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 国库转账治理事件。
 *
 * <p>当 {@code TREASURY_SPEND} 类型提案通过并触发转账时发布。
 * 监听方可据此事件驱动对账、风控告警、链上确认等下游流程。
 *
 * <p>事件载荷包含：
 * <ul>
 *   <li>{@code proposalId} — 关联提案 ID</li>
 *   <li>{@code targetAddress} — 收款地址</li>
 *   <li>{@code amount} — 转账金额</li>
 *   <li>{@code token} — 代币标识（如 USDT）</li>
 *   <li>{@code chain} — 目标链（如 ethereum）</li>
 *   <li>{@code txHash} — 链上交易哈希（执行成功时填充）</li>
 *   <li>{@code timestamp} — 事件发布时间</li>
 * </ul>
 *
 * @since 2.0.0
 */
public class TreasurySpendEvent {

    /** 关联提案 ID */
    private final String proposalId;

    /** 收款地址 */
    private final String targetAddress;

    /** 转账金额 */
    private final BigDecimal amount;

    /** 代币标识 */
    private final String token;

    /** 目标链 */
    private final String chain;

    /** 链上交易哈希 */
    private final String txHash;

    /** 事件发布时间 */
    private final Instant timestamp;

    /** 关联提案（可选） */
    private final Proposal proposal;

    /**
     * 构造国库转账事件。
     *
     * @param proposalId    关联提案 ID
     * @param targetAddress 收款地址
     * @param amount        转账金额
     * @param token         代币标识
     * @param chain         目标链
     * @param txHash        链上交易哈希（可为 {@code null}，转账未完成时）
     * @param proposal      关联提案（可为 {@code null}）
     */
    public TreasurySpendEvent(String proposalId, String targetAddress, BigDecimal amount,
                              String token, String chain, String txHash, Proposal proposal) {
        this.proposalId = proposalId;
        this.targetAddress = targetAddress;
        this.amount = amount;
        this.token = token;
        this.chain = chain;
        this.txHash = txHash;
        this.proposal = proposal;
        this.timestamp = Instant.now();
    }

    /** @return 关联提案 ID */
    public String getProposalId() {
        return proposalId;
    }

    /** @return 收款地址 */
    public String getTargetAddress() {
        return targetAddress;
    }

    /** @return 转账金额 */
    public BigDecimal getAmount() {
        return amount;
    }

    /** @return 代币标识 */
    public String getToken() {
        return token;
    }

    /** @return 目标链 */
    public String getChain() {
        return chain;
    }

    /** @return 链上交易哈希 */
    public String getTxHash() {
        return txHash;
    }

    /** @return 事件发布时间 */
    public Instant getTimestamp() {
        return timestamp;
    }

    /** @return 关联提案 */
    public Proposal getProposal() {
        return proposal;
    }

    @Override
    public String toString() {
        return "TreasurySpendEvent{proposalId='" + proposalId + "', targetAddress='" + targetAddress
                + "', amount=" + amount + ", token='" + token + "', chain='" + chain
                + "', txHash='" + txHash + "', timestamp=" + timestamp + '}';
    }
}
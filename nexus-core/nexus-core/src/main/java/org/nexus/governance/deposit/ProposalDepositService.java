package org.nexus.governance.deposit;

import org.nexus.consensus.pos.StakingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提案保证金服务。
 *
 * <p>提案人提交提案时需质押保证金（proposal deposit），以抑制垃圾提案与恶意提案：</p>
 * <ul>
 *   <li><b>锁定</b>：提交提案时从提案人质押中扣除保证金并锁定</li>
 *   <li><b>退还</b>：提案通过（含执行成功）则全额退还提案人</li>
 *   <li><b>罚没</b>：提案失败、被否决或判定为恶意则罚没保证金（不退还）</li>
 * </ul>
 *
 * <h3>结算时机</h3>
 * <p>典型流程：提交提案时 {@link #lockDeposit}，提案终态时调用 {@link #settleDeposit}：
 * 通过/执行成功退还，否决/失败/过期罚没。</p>
 *
 * @since 1.4
 */
@Component
public class ProposalDepositService {

    private static final Logger logger = LoggerFactory.getLogger(ProposalDepositService.class);

    @Autowired
    private StakingService stakingService;

    /** proposalId -> 保证金记录 */
    private final ConcurrentHashMap<String, DepositRecord> deposits = new ConcurrentHashMap<>();

    /**
     * 锁定提案保证金：从提案人质押中扣除指定金额并登记。
     *
     * @param proposalId 提案 ID
     * @param proposer   提案人地址
     * @param amount     保证金金额
     * @return 锁定成功返回 true；金额非正、质押不足、重复锁定或参数非法返回 false
     */
    public boolean lockDeposit(String proposalId, String proposer, BigDecimal amount) {
        if (proposalId == null || proposer == null || amount == null || amount.signum() <= 0) {
            logger.warn("Lock deposit rejected: invalid parameters proposalId={} proposer={} amount={}",
                    proposalId, proposer, amount);
            return false;
        }
        if (deposits.containsKey(proposalId)) {
            logger.warn("Lock deposit rejected: proposal {} already has a deposit", proposalId);
            return false;
        }
        BigDecimal stake = stakingService.getStake(proposer);
        if (stake == null || stake.compareTo(amount) < 0) {
            logger.warn("Lock deposit rejected: proposer {} stake {} < deposit {}", proposer, stake, amount);
            return false;
        }
        try {
            stakingService.unstake(proposer, amount);
        } catch (Exception e) {
            logger.warn("Lock deposit rejected: unstake failed for proposer {}", proposer, e);
            return false;
        }
        DepositRecord record = new DepositRecord(proposalId, proposer, amount, DepositStatus.LOCKED, Instant.now());
        deposits.put(proposalId, record);
        logger.info("Deposit locked: proposal={} proposer={} amount={}", proposalId, proposer, amount);
        return true;
    }

    /**
     * 退还保证金：将锁定金额返还提案人质押。
     *
     * @param proposalId 提案 ID
     * @return 退还成功返回 true；无锁定记录或已结算返回 false
     */
    public boolean refundDeposit(String proposalId) {
        DepositRecord record = deposits.get(proposalId);
        if (record == null || record.status != DepositStatus.LOCKED) {
            logger.warn("Refund rejected: proposal {} has no locked deposit", proposalId);
            return false;
        }
        try {
            stakingService.stake(record.proposer, record.amount);
        } catch (Exception e) {
            logger.warn("Refund failed: stake failed for proposer {}", record.proposer, e);
            return false;
        }
        record.status = DepositStatus.REFUNDED;
        logger.info("Deposit refunded: proposal={} proposer={} amount={}", proposalId, record.proposer, record.amount);
        return true;
    }

    /**
     * 罚没保证金：锁定金额不退还。
     *
     * @param proposalId 提案 ID
     * @return 罚没成功返回 true；无锁定记录或已结算返回 false
     */
    public boolean slashDeposit(String proposalId) {
        DepositRecord record = deposits.get(proposalId);
        if (record == null || record.status != DepositStatus.LOCKED) {
            logger.warn("Slash rejected: proposal {} has no locked deposit", proposalId);
            return false;
        }
        record.status = DepositStatus.SLASHED;
        logger.info("Deposit slashed: proposal={} proposer={} amount={}", proposalId, record.proposer, record.amount);
        return true;
    }

    /**
     * 根据提案结果结算保证金：通过退还，失败罚没。
     *
     * @param proposalId 提案 ID
     * @param passed     提案是否通过/成功
     * @return 结算成功返回 true；无锁定记录返回 false
     */
    public boolean settleDeposit(String proposalId, boolean passed) {
        if (passed) {
            return refundDeposit(proposalId);
        }
        return slashDeposit(proposalId);
    }

    /**
     * 查询指定提案的保证金记录。
     *
     * @param proposalId 提案 ID
     * @return 保证金记录；不存在返回 {@link Optional#empty()}
     */
    public Optional<DepositRecord> getDeposit(String proposalId) {
        return Optional.ofNullable(deposits.get(proposalId));
    }

    /** 保证金状态枚举 */
    public enum DepositStatus {
        /** 已锁定 */
        LOCKED,
        /** 已退还 */
        REFUNDED,
        /** 已罚没 */
        SLASHED
    }

    /** 保证金记录 */
    public static final class DepositRecord {
        /** 提案 ID */
        private final String proposalId;
        /** 提案人 */
        private final String proposer;
        /** 金额 */
        private final BigDecimal amount;
        /** 锁定时间 */
        private final Instant lockedAt;
        /** 状态（可变，结算时更新） */
        private volatile DepositStatus status;

        DepositRecord(String proposalId, String proposer, BigDecimal amount, DepositStatus status, Instant lockedAt) {
            this.proposalId = proposalId;
            this.proposer = proposer;
            this.amount = amount;
            this.status = status;
            this.lockedAt = lockedAt;
        }

        public String getProposalId() {
            return proposalId;
        }

        public String getProposer() {
            return proposer;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public Instant getLockedAt() {
            return lockedAt;
        }

        public DepositStatus getStatus() {
            return status;
        }
    }
}
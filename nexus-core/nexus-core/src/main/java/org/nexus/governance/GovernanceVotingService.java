package org.nexus.governance;

import org.nexus.consensus.pos.StakingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 治理投票服务。
 *
 * <p>投票权重等于投票人的质押量，无质押者投票被拒绝。
 * 同一提案同一投票人重复投票将覆盖先前记录（最后票为准）。</p>
 *
 * @since 1.2
 */
@Component
public class GovernanceVotingService {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceVotingService.class);

    @Autowired
    private StakingService stakingService;

    /** proposalId -> (voter -> vote record) */
    private final Map<String, Map<String, VoteRecord>> votes = new ConcurrentHashMap<>();

    /**
     * 对指定提案投票。
     *
     * @param proposalId 提案 ID
     * @param voter      投票人地址
     * @param option     投票选项
     * @param now        当前时间
     */
    public void vote(String proposalId, String voter, VoteOption option, Instant now) {
        if (proposalId == null || voter == null || option == null) {
            return;
        }
        BigDecimal weight = stakingService.getStake(voter);
        if (weight.signum() <= 0) {
            logger.warn("Vote rejected: voter {} has no stake", voter);
            return;
        }
        votes.computeIfAbsent(proposalId, k -> new ConcurrentHashMap<>())
                .put(voter, new VoteRecord(option, weight, now));
        logger.info("Vote {} by {} on {} weight={}", option, voter, proposalId, weight);
    }

    /**
     * 统计指定提案的投票结果。
     *
     * @param proposalId 提案 ID
     * @return 投票统计
     */
    public VoteTally tally(String proposalId) {
        VoteTally tally = new VoteTally();
        Map<String, VoteRecord> map = votes.get(proposalId);
        if (map == null) {
            return tally;
        }
        for (VoteRecord record : map.values()) {
            switch (record.option) {
                case YES:
                    tally.yes = tally.yes.add(record.weight);
                    break;
                case NO:
                    tally.no = tally.no.add(record.weight);
                    break;
                case ABSTAIN:
                    tally.abstain = tally.abstain.add(record.weight);
                    break;
                default:
                    break;
            }
        }
        return tally;
    }

    /**
     * 判断指定投票人是否已对提案投票。
     *
     * @param proposalId 提案 ID
     * @param voter      投票人地址
     * @return 已投票返回 true
     */
    public boolean hasVoted(String proposalId, String voter) {
        Map<String, VoteRecord> map = votes.get(proposalId);
        return map != null && map.containsKey(voter);
    }

    /**
     * 清空指定提案的投票记录（提案执行或过期后调用）。
     *
     * @param proposalId 提案 ID
     */
    public void clearVotes(String proposalId) {
        votes.remove(proposalId);
    }

    /** 单条投票记录 */
    public static final class VoteRecord {
        private final VoteOption option;
        private final BigDecimal weight;
        private final Instant timestamp;

        VoteRecord(VoteOption option, BigDecimal weight, Instant timestamp) {
            this.option = option;
            this.weight = weight;
            this.timestamp = timestamp;
        }

        public VoteOption getOption() {
            return option;
        }

        public BigDecimal getWeight() {
            return weight;
        }

        public Instant getTimestamp() {
            return timestamp;
        }
    }

    /** 投票统计结果 */
    public static final class VoteTally {
        private BigDecimal yes = BigDecimal.ZERO;
        private BigDecimal no = BigDecimal.ZERO;
        private BigDecimal abstain = BigDecimal.ZERO;

        public BigDecimal getYes() {
            return yes;
        }

        public BigDecimal getNo() {
            return no;
        }

        public BigDecimal getAbstain() {
            return abstain;
        }

        /**
         * 判断提案是否通过：赞成多于反对且总票数达到法定人数。
         *
         * @param quorum 法定人数门槛
         * @return 通过返回 true
         */
        public boolean passes(BigDecimal quorum) {
            BigDecimal total = yes.add(no).add(abstain);
            return yes.compareTo(no) > 0 && total.compareTo(quorum) >= 0;
        }
    }
}
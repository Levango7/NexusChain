package org.nexus.governance;

import org.nexus.consensus.pos.StakingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 治理投票服务。
 *
 * <p>投票权重等于投票人的质押量，无质押者投票被拒绝。
 * 同一提案同一投票人重复投票将覆盖先前记录（最后票为准）。</p>
 *
 * <h3>通过判定（双门槛）</h3>
 * <p>提案通过需同时满足：</p>
 * <ol>
 *   <li>赞成票多于反对票：{@code yes > no}</li>
 *   <li>赞成票达到法定人数：{@code yes >= quorum}</li>
 *   <li>总票数达到法定人数的 1.5 倍：{@code (yes+no+abstain) >= quorum * 1.5}</li>
 * </ol>
 * <p>abstain（弃权）票不计入赞成票门槛，仅计入总票数门槛。</p>
 *
 * @since 1.2
 */
@Component
public class GovernanceVotingService {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceVotingService.class);

    /** 总票数门槛倍数 */
    private static final BigDecimal TOTAL_TURNOUT_MULTIPLIER = new BigDecimal("1.5");

    @Autowired
    private StakingService stakingService;

    @Autowired
    private VoteRecordRepository voteRepository;

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
        voteRepository.recordVote(proposalId, voter, new VoteRecord(option, weight, now));
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
        Map<String, VoteRecord> map = voteRepository.getVotes(proposalId);
        for (VoteRecord record : map.values()) {
            VoteOption effective = record.effectiveVote();
            if (effective == null) {
                // commit 阶段未揭示的票不计入统计
                continue;
            }
            switch (effective) {
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
        return voteRepository.hasVoted(proposalId, voter);
    }

    /**
     * 清空指定提案的投票记录（提案执行或过期后调用）。
     *
     * @param proposalId 提案 ID
     */
    public void clearVotes(String proposalId) {
        voteRepository.clearVotes(proposalId);
    }

    /**
     * 工厂方法：创建 commit 阶段投票记录（仅含哈希，未揭示）。
     *
     * @param commitHash 投票哈希
     * @param weight     投票权重
     * @param timestamp  commit 时间
     * @return commit 阶段记录
     */
    public static VoteRecord newCommitRecord(String commitHash, BigDecimal weight, Instant timestamp) {
        return new VoteRecord(commitHash, weight, timestamp);
    }

    /**
     * 工厂方法：基于 commit 记录创建 reveal 阶段记录（揭示真实投票与盐）。
     *
     * <p>保留 commit 时的权重与时间戳，置 {@code revealed=true}。</p>
     *
     * @param commit       原 commit 记录
     * @param revealedVote 揭示的真实投票
     * @param salt         揭示的盐
     * @return reveal 阶段记录
     */
    public static VoteRecord newRevealedRecord(VoteRecord commit, VoteOption revealedVote, String salt) {
        if (commit == null) {
            return new VoteRecord(revealedVote, BigDecimal.ZERO, Instant.now());
        }
        return new VoteRecord(null, commit.getWeight(), commit.getTimestamp(),
                commit.getCommitHash(), revealedVote, salt, true);
    }

    /**
     * 单条投票记录。
     *
     * <p>支持两种模式：</p>
     * <ul>
     *   <li>直接投票：构造时携带 {@code option}，{@code revealed=true}，{@code revealedVote=option}</li>
     *   <li>commit-reveal 两阶段投票：commit 阶段仅携带 {@code commitHash}（{@code revealed=false}），
     *       reveal 阶段揭示 {@code revealedVote} 与 {@code salt} 并校验哈希匹配</li>
     * </ul>
     *
     * <p>向后兼容：原三参构造器语义不变，直接投票视为已揭示。</p>
     */
    public static final class VoteRecord {
        /** 直接投票时的选项（commit 阶段为 null） */
        private final VoteOption option;
        /** 投票权重 */
        private final BigDecimal weight;
        /** 投票时间戳 */
        private final Instant timestamp;
        /** commit 阶段提交的哈希值（直接投票为 null） */
        private final String commitHash;
        /** reveal 阶段揭示的真实投票选项（直接投票时等于 option） */
        private final VoteOption revealedVote;
        /** reveal 阶段揭示的盐值 */
        private final String salt;
        /** 是否已揭示（直接投票视为已揭示） */
        private final boolean revealed;

        /**
         * 直接投票构造器（向后兼容）。
         *
         * @param option    投票选项
         * @param weight    投票权重
         * @param timestamp 投票时间
         */
        VoteRecord(VoteOption option, BigDecimal weight, Instant timestamp) {
            this(option, weight, timestamp, null, option, null, true);
        }

        /**
         * commit 阶段构造器：仅提交哈希，真实投票尚未揭示。
         *
         * @param commitHash 投票哈希
         * @param weight     投票权重
         * @param timestamp  commit 时间
         */
        VoteRecord(String commitHash, BigDecimal weight, Instant timestamp) {
            this(null, weight, timestamp, commitHash, null, null, false);
        }

        /**
         * 全参构造器（reveal 阶段或内部使用）。
         *
         * @param option      直接投票选项（可 null）
         * @param weight      权重
         * @param timestamp   时间戳
         * @param commitHash  commit 哈希
         * @param revealedVote 揭示的真实投票
         * @param salt        盐
         * @param revealed    是否已揭示
         */
        VoteRecord(VoteOption option, BigDecimal weight, Instant timestamp,
                   String commitHash, VoteOption revealedVote, String salt, boolean revealed) {
            this.option = option;
            this.weight = weight;
            this.timestamp = timestamp;
            this.commitHash = commitHash;
            this.revealedVote = revealedVote;
            this.salt = salt;
            this.revealed = revealed;
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

        /** commit 阶段提交的哈希值；直接投票返回 null */
        public String getCommitHash() {
            return commitHash;
        }

        /** reveal 阶段揭示的真实投票选项；未揭示返回 null */
        public VoteOption getRevealedVote() {
            return revealedVote;
        }

        /** reveal 阶段揭示的盐值；未揭示或直接投票返回 null */
        public String getSalt() {
            return salt;
        }

        /** 是否已揭示（直接投票视为已揭示） */
        public boolean isRevealed() {
            return revealed;
        }

        /**
         * 返回计票时使用的有效投票选项。
         *
         * <p>优先取 {@code revealedVote}（commit-reveal 模式），否则取 {@code option}（直接投票）。
         * commit 阶段未揭示时返回 null，计票时跳过。</p>
         *
         * @return 有效投票选项；未揭示返回 null
         */
        public VoteOption effectiveVote() {
            return revealedVote != null ? revealedVote : option;
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
         * 判断提案是否通过（双门槛）。
         *
         * <p>需同时满足：{@code yes > no}、{@code yes >= quorum}、
         * {@code (yes+no+abstain) >= quorum * 1.5}。abstain 不计入赞成票门槛。</p>
         *
         * @param quorum 法定人数门槛
         * @return 通过返回 true
         */
        public boolean passes(BigDecimal quorum) {
            if (yes.compareTo(no) <= 0) {
                return false;
            }
            if (yes.compareTo(quorum) < 0) {
                return false;
            }
            BigDecimal total = yes.add(no).add(abstain);
            BigDecimal turnoutThreshold = quorum.multiply(TOTAL_TURNOUT_MULTIPLIER);
            return total.compareTo(turnoutThreshold) >= 0;
        }
    }
}

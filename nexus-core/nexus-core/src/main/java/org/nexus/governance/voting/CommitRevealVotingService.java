package org.nexus.governance.voting;

import org.nexus.consensus.pos.StakingService;
import org.nexus.governance.GovernanceVotingService;
import org.nexus.governance.GovernanceVotingService.VoteRecord;
import org.nexus.governance.VoteOption;
import org.nexus.governance.VoteRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;

/**
 * commit-reveal 两阶段投票服务。
 *
 * <p>为防止跟投（vote copying）与贿赂后强制投票（bribery attack），投票分两阶段进行：</p>
 * <ol>
 *   <li><b>commit 阶段</b>：投票人提交 {@code commitHash = SHA-256(voter|proposalId|option|salt)}，
 *       链上仅暴露哈希，他人无法获知真实投票意图，从而无法跟投或验证贿赂是否得逞</li>
 *   <li><b>reveal 阶段</b>：投票期结束后，投票人揭示 {@code option} 与 {@code salt}，
 *       服务端重新计算哈希并与 commit 阶段哈希比对，匹配则计票</li>
 * </ol>
 *
 * <h3>权重锁定</h3>
 * <p>投票权重在 commit 阶段基于当时质押快照锁定，防止 reveal 阶段临时改质押操纵投票结果。</p>
 *
 * <h3>防重放</h3>
 * <p>同一投票人对同一提案仅能 commit 一次、reveal 一次；重复操作被拒绝。</p>
 *
 * @since 1.4
 */
@Component
public class CommitRevealVotingService {

    private static final Logger logger = LoggerFactory.getLogger(CommitRevealVotingService.class);

    @Autowired
    private StakingService stakingService;

    @Autowired
    private VoteRecordRepository voteRepository;

    /**
     * 计算 commit 哈希：{@code SHA-256(voter|proposalId|option|salt)} 的十六进制字符串。
     *
     * <p>客户端应使用本方法在本地计算哈希后通过 {@link #commit} 提交，
     * 确保真实投票意图在 commit 阶段不上链。</p>
     *
     * @param voter      投票人地址
     * @param proposalId 提案 ID
     * @param option     真实投票选项
     * @param salt       随机盐值（应足够长以防暴力破解）
     * @return 哈希十六进制字符串
     */
    public static String computeCommitHash(String voter, String proposalId, VoteOption option, String salt) {
        if (voter == null || proposalId == null || option == null || salt == null) {
            throw new IllegalArgumentException("commit hash inputs must not be null");
        }
        String payload = voter + "|" + proposalId + "|" + option.name() + "|" + salt;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 必备算法，理论上不会缺失
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 将字节数组转为小写十六进制字符串。
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * commit 阶段：提交投票哈希。
     *
     * <p>权重在此时基于质押快照锁定。无质押者 commit 被拒绝。
     * 同一投票人重复 commit 被拒绝（防重放）。</p>
     *
     * @param proposalId 提案 ID
     * @param voter      投票人地址
     * @param commitHash 投票哈希（客户端本地计算）
     * @param now        当前时间
     * @return commit 成功返回 true；无质押、重复 commit 或参数非法返回 false
     */
    public boolean commit(String proposalId, String voter, String commitHash, Instant now) {
        if (proposalId == null || voter == null || commitHash == null || now == null) {
            logger.warn("Commit rejected: invalid parameters proposalId={} voter={}", proposalId, voter);
            return false;
        }
        if (voteRepository.hasVoted(proposalId, voter)) {
            logger.warn("Commit rejected: voter {} already committed/revealed on {}", voter, proposalId);
            return false;
        }
        BigDecimal weight = stakingService.getStake(voter);
        if (weight.signum() <= 0) {
            logger.warn("Commit rejected: voter {} has no stake", voter);
            return false;
        }
        VoteRecord commitRecord = GovernanceVotingService.newCommitRecord(commitHash, weight, now);
        voteRepository.recordVote(proposalId, voter, commitRecord);
        logger.info("Commit by {} on {} (weight locked={})", voter, proposalId, weight);
        return true;
    }

    /**
     * reveal 阶段：揭示真实投票与盐，校验哈希匹配后落票。
     *
     * <p>校验 {@code computeCommitHash(voter, proposalId, option, salt)} 与 commit 阶段哈希一致，
     * 匹配则以 commit 时锁定的权重写入已揭示记录；不匹配或未 commit 则拒绝。</p>
     *
     * @param proposalId 提案 ID
     * @param voter      投票人地址
     * @param option     揭示的真实投票选项
     * @param salt       揭示的盐值
     * @return reveal 成功返回 true；哈希不匹配、未 commit、已 reveal 或参数非法返回 false
     */
    public boolean reveal(String proposalId, String voter, VoteOption option, String salt) {
        if (proposalId == null || voter == null || option == null || salt == null) {
            logger.warn("Reveal rejected: invalid parameters proposalId={} voter={}", proposalId, voter);
            return false;
        }
        Map<String, VoteRecord> votes = voteRepository.getVotes(proposalId);
        VoteRecord existing = votes.get(voter);
        if (existing == null) {
            logger.warn("Reveal rejected: voter {} has no commit on {}", voter, proposalId);
            return false;
        }
        if (existing.isRevealed()) {
            logger.warn("Reveal rejected: voter {} already revealed on {}", voter, proposalId);
            return false;
        }
        String expectedHash = computeCommitHash(voter, proposalId, option, salt);
        if (!expectedHash.equals(existing.getCommitHash())) {
            logger.warn("Reveal rejected: hash mismatch for voter {} on {} (expected={} got={})",
                    voter, proposalId, existing.getCommitHash(), expectedHash);
            return false;
        }
        VoteRecord revealed = GovernanceVotingService.newRevealedRecord(existing, option, salt);
        voteRepository.recordVote(proposalId, voter, revealed);
        logger.info("Reveal by {} on {} -> {}", voter, proposalId, option);
        return true;
    }

    /**
     * 判断投票人是否已 commit（无论是否 reveal）。
     *
     * @param proposalId 提案 ID
     * @param voter      投票人
     * @return 已 commit 返回 true
     */
    public boolean hasCommitted(String proposalId, String voter) {
        return voteRepository.hasVoted(proposalId, voter);
    }

    /**
     * 判断投票人是否已完成 reveal。
     *
     * @param proposalId 提案 ID
     * @param voter      投票人
     * @return 已 reveal 返回 true
     */
    public boolean isRevealed(String proposalId, String voter) {
        VoteRecord record = voteRepository.getVotes(proposalId).get(voter);
        return record != null && record.isRevealed();
    }
}
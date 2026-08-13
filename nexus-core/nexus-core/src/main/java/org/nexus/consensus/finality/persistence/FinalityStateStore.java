package org.nexus.consensus.finality.persistence;

import java.util.Map;
import java.util.Set;

/**
 * 最终性状态持久化接口（ADR-030 P0-1：节点重启不丢失已最终化检查点）。
 *
 * <p>设计原则（对齐项目既有 {@code PaymentStateStore} 模式）：</p>
 * <ul>
 *   <li>只持久化<b>事实性状态</b>：投票记录 + 最终化标记；权重是衍生值
 *       （由 ValidatorRegistry/StakingService 重算），不持久化避免双写不一致</li>
 *   <li>幂等：同检查点重复记录覆盖而非报错（投票天然幂等语义）</li>
 *   <li>key 约定：{@code epoch|checkpointHex}，与 {@code FinalityGadget} 内部 key 一致</li>
 * </ul>
 *
 * <p>实现：{@link InMemoryFinalityStateStore}（测试/单机，等价现有行为）、
 * {@link JdbcFinalityStateStore}（Postgres 持久化，生产）。</p>
 */
public interface FinalityStateStore {

    /**
     * 记录一次投票（幂等覆盖）。
     *
     * @param epoch            共识 epoch
     * @param checkpointHash   检查点区块哈希（原始字节）
     * @param validatorAddress 投票验证人地址
     */
    void recordVote(long epoch, byte[] checkpointHash, String validatorAddress);

    /**
     * 标记检查点最终化（幂等）。
     */
    void markFinalized(long epoch, byte[] checkpointHash);

    /**
     * 检查某检查点是否已最终化。
     */
    boolean isFinalized(long epoch, byte[] checkpointHash);

    /**
     * 加载某检查点的全部投票者地址（重启恢复用）。
     */
    Set<String> loadVoters(long epoch, byte[] checkpointHash);

    /**
     * 加载全部最终化检查点（key = epoch|checkpointHex，value = true）。
     * 启动时重建 {@code finalizedCheckpoints} 内存缓存用。
     */
    Map<String, Boolean> loadAllFinalized();

    /**
     * 加载全部投票记录（key = epoch|checkpointHex，value = 投票者集合）。
     * 启动时重建 {@code epochCheckpointVoters/epochCheckpointVotes} 用。
     */
    Map<String, Set<String>> loadAllVotes();
}
package org.nexus.consensus.finality.persistence;

import org.nexus.consensus.finality.Vote;

import java.util.List;
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
     * 记录一次投票并**连同签名与公钥**落库（PLAN-018）。
     *
     * <p>为什么必须连签名一起存：只存 (epoch, checkpoint, validator) 时，
     * 节点重启后只能恢复"谁投过"，恢复不出"投的是什么签名"——
     * {@code FinalityGadget.tryFinalize} 的聚合验签因此无票可验，
     * 检查点会永远停在"权重够了但验签不了"，且无法在重启后补最终化。</p>
     *
     * <p>默认实现退化为旧的 3 参版本（丢弃签名），保证既有实现与测试替身
     * 不必同步改造；生产实现（JDBC）必须覆写为真正落库。</p>
     *
     * @param epoch            共识 epoch
     * @param checkpointHash   检查点区块哈希（原始字节）
     * @param validatorAddress 投票验证人地址
     * @param signature        Ed25519 签名（可为 null，表示未知）
     * @param publicKey        投票者公钥（可为 null）
     */
    default void recordVote(long epoch, byte[] checkpointHash, String validatorAddress,
                            byte[] signature, byte[] publicKey) {
        recordVote(epoch, checkpointHash, validatorAddress);
    }

    /**
     * 加载**完整投票对象**（含签名），key = epoch|checkpointHex。
     *
     * <p>供节点重启后恢复聚合验签所需的 Vote 集合。默认返回空表，
     * 表示"本实现不支持恢复签名对象"（旧行为：重启后不恢复签名）。</p>
     */
    default Map<String, List<Vote>> loadAllVoteObjects() {
        return java.util.Collections.emptyMap();
    }

    /**
     * 把一张待补写的票放进持久化 outbox（PLAN-018）。
     *
     * <p>语义：这张票**还没**写进 {@code finality_votes}，但必须记住它，
     * 以免进程重启后丢失"写库意图"。默认实现为空操作（不支持持久化 outbox）。</p>
     */
    default void enqueuePendingVote(FinalityOutboxEntry entry) {
        // 默认不支持：调用方需自行降级到内存队列
    }

    /**
     * 读取待补写队列（按创建时间升序，最多 limit 条）。
     *
     * <p>默认返回空列表（不支持持久化 outbox）。</p>
     */
    default List<FinalityOutboxEntry> loadPendingVotes(int limit) {
        return java.util.Collections.emptyList();
    }

    /**
     * 删除一条已成功补写的 outbox 记录（幂等）。
     */
    default void removePendingVote(FinalityOutboxEntry entry) {
        // 默认不支持
    }

    /**
     * 记录一次补写失败（累计尝试次数与最近错误），便于运维观测。
     */
    default void markPendingVoteFailure(FinalityOutboxEntry entry, String error) {
        // 默认不支持
    }

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
     * 启动时重建 {@code epochCheckpointVoters/epochCheckpointWeights} 用。
     *
     * <p>PLAN-018 起：完整 {@code Vote} 签名对象**已随投票落库**
     * （见 {@link #recordVote(long, byte[], String, byte[], byte[])} 与
     * {@link #loadAllVoteObjects()}），本方法仍只返回投票者地址集合。</p>
     */
    Map<String, Set<String>> loadAllVotes();
}
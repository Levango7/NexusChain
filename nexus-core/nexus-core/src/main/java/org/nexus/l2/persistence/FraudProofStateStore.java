package org.nexus.l2.persistence;

import org.nexus.l2.ChallengeBond;
import org.nexus.l2.FraudProof;
import org.nexus.l2.RollupBatch;

import java.time.Instant;
import java.util.List;

/**
 * 欺诈证明批次/挑战状态持久化抽象（PLAN-014 Phase 4）。
 *
 * <p>背景：{@code FraudProofVerifier} 与 {@code ChallengeConflictResolver} 的批次、
 * bond、挑战冲突状态此前全部是进程内 {@code ConcurrentHashMap}，进程重启即丢失，
 * 与"批次/挑战状态持久化"验收目标不符。</p>
 *
 * <p>本接口只负责状态存取，不参与共识判断。默认实现为
 * {@link JdbcFraudProofStateStore}（PostgreSQL；H2 PostgreSQL 兼容模式用于测试）；
 * 非 Spring 手工装配或不注入 store 时，调用方保持原有纯内存行为。</p>
 *
 * <p><b>作用域边界（诚实记录）</b>：本次持久化覆盖 批次实体/提交时间/提交者、
 * 挑战 bond、first-valid-wins 冲突状态；{@code ChallengePeriodPolicy} 的
 * "可疑行为延期"标记与 L1 桥的存款/提款/已提交根仍为进程内存（见 PLAN-014 Phase 4 记录）。</p>
 *
 * @since 2.74
 */
public interface FraudProofStateStore {

    /**
     * 写入/更新批次快照。
     *
     * @param batch      批次实体（batchId 必须非空）
     * @param submitter  提交者地址（可为 null）
     * @param submitTime 提交时间（挑战窗口起点）
     */
    void saveBatch(RollupBatch batch, String submitter, Instant submitTime);

    /**
     * 写入/更新挑战 bond。
     *
     * @param bond 挑战 bond（challengerId 必须非空）
     */
    void saveBond(ChallengeBond bond);

    /**
     * 写入/更新批次的 first-valid-wins 冲突状态。
     *
     * @param conflict 冲突快照
     */
    void saveConflict(ConflictRecord conflict);

    /**
     * 删除批次的冲突状态（finalize / clear 后调用）。
     *
     * @param batchId 批次 ID
     */
    void deleteConflict(long batchId);

    /**
     * 读取全部持久化状态（启动恢复）。
     *
     * @return 快照；空库返回空列表而非 null
     */
    Snapshot load();

    /** 批次持久化记录。 */
    record BatchRecord(RollupBatch batch, String submitter, Instant submitTime) {
    }

    /** 挑战冲突持久化记录。 */
    record ConflictRecord(long batchId,
                          String firstValidChallenger,
                          FraudProof firstValidProof,
                          List<String> allChallengers,
                          List<String> refundedChallengers,
                          List<String> slashedChallengers) {

        public ConflictRecord {
            allChallengers = allChallengers == null ? List.of() : List.copyOf(allChallengers);
            refundedChallengers = refundedChallengers == null ? List.of() : List.copyOf(refundedChallengers);
            slashedChallengers = slashedChallengers == null ? List.of() : List.copyOf(slashedChallengers);
        }
    }

    /** 全量恢复快照。 */
    record Snapshot(List<BatchRecord> batches,
                    List<ChallengeBond> bonds,
                    List<ConflictRecord> conflicts) {

        public Snapshot {
            batches = batches == null ? List.of() : List.copyOf(batches);
            bonds = bonds == null ? List.of() : List.copyOf(bonds);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }
}

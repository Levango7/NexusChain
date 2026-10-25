package org.nexus.bridge.safety;

import org.nexus.bridge.repository.BridgeDailyUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 日限额用量落库实现（PLAN-038）：单行表 {@code bridge_daily_usage} 持有当前窗口。
 *
 * <h2>事务语义（关键设计）</h2>
 * <p>预留/归还均在 <b>REQUIRES_NEW</b> 事务中执行：立即提交、独立于桥操作的
 * 业务事务——忠实复刻既有 in-memory 语义（配额先占；业务回滚由
 * {@code BridgeServiceImpl} 的 afterCompletion 补偿归还）。若让写参与业务事务，
 * 回滚会自动退回配额、再叠加补偿会导致<b>双重归还</b>（限额被低估）。</p>
 *
 * <h2>并发与重启</h2>
 * <ul>
 *   <li>行级悲观写锁（{@link BridgeDailyUsageRepository#findByIdForUpdate}）
 *       让多副本并发预留串行化 ⇒ 日限额在多副本部署下是<b>全局</b>的
 *       （历史 in-memory 实现为每副本一份，N 副本可允出 N 倍）；</li>
 *   <li>状态落库 ⇒ 进程重启不清零（历史实现重启即绕过当日限额）；</li>
 *   <li>冷启动插入竞争（两副本同时建行）由主键约束兜底 + 有界重试。</li>
 * </ul>
 *
 * @since 2.76.0
 */
public class JdbcDailyUsageStore implements DailyUsageStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcDailyUsageStore.class);

    /** 单例行主键（与 migration V6 注释一致）。 */
    static final long SINGLETON_ID = 1L;

    /** 冷启动插入竞争的重试次数。 */
    private static final int INSERT_RETRIES = 3;

    private final BridgeDailyUsageRepository repository;
    private final TransactionTemplate requiresNew;

    public JdbcDailyUsageStore(BridgeDailyUsageRepository repository,
                               PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public long currentUsed(long nowMillis) {
        Optional<BridgeDailyUsageRecord> record = repository.findById(SINGLETON_ID);
        if (record.isEmpty() || nowMillis - record.get().getWindowStart() > WINDOW_MILLIS) {
            return 0L;
        }
        return record.get().getUsed();
    }

    @Override
    public boolean tryReserve(long amount, long limit, long nowMillis) {
        for (int attempt = 0; attempt < INSERT_RETRIES; attempt++) {
            try {
                Boolean ok = requiresNew.execute(status -> doReserve(amount, limit, nowMillis));
                return Boolean.TRUE.equals(ok);
            } catch (DataIntegrityViolationException e) {
                // 两副本同时冷启动的插入竞争：重试走已存在的行
                log.debug("daily usage insert race, retrying ({}/{})", attempt + 1, INSERT_RETRIES);
            }
        }
        throw new IllegalStateException("daily usage reserve failed after insert races");
    }

    private boolean doReserve(long amount, long limit, long nowMillis) {
        Optional<BridgeDailyUsageRecord> found = repository.findByIdForUpdate(SINGLETON_ID);
        if (found.isEmpty()) {
            if (amount > limit) {
                return false; // 新窗口亦超额：不建行（等价历史行为：0 + amount > limit 即拒绝）
            }
            BridgeDailyUsageRecord fresh =
                    new BridgeDailyUsageRecord(SINGLETON_ID, nowMillis, amount, Instant.now());
            repository.saveAndFlush(fresh);
            return true;
        }
        BridgeDailyUsageRecord record = found.get();
        boolean rolled = nowMillis - record.getWindowStart() > WINDOW_MILLIS;
        if (rolled) {
            record.setWindowStart(nowMillis);
            record.setUsed(0L);
        }
        if (record.getUsed() + amount > limit) {
            if (rolled) {
                // 过期窗口 + 本次超额：仍把窗口滚动落库（与历史 lazy 重置的副作用一致）
                record.setUpdatedAt(Instant.now());
                repository.saveAndFlush(record);
            }
            return false;
        }
        record.setUsed(record.getUsed() + amount);
        record.setUpdatedAt(Instant.now());
        repository.saveAndFlush(record);
        return true;
    }

    @Override
    public void release(long amount, long nowMillis) {
        requiresNew.executeWithoutResult(status -> {
            Optional<BridgeDailyUsageRecord> found = repository.findByIdForUpdate(SINGLETON_ID);
            if (found.isEmpty()) {
                return;
            }
            BridgeDailyUsageRecord record = found.get();
            if (nowMillis - record.getWindowStart() > WINDOW_MILLIS) {
                return; // 旧窗口余量已随滚动作废（与 InMemory 实现对齐）
            }
            record.setUsed(Math.max(0L, record.getUsed() - amount));
            record.setUpdatedAt(Instant.now());
            repository.saveAndFlush(record);
        });
    }
}

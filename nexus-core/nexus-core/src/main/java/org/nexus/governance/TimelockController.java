package org.nexus.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 时间锁控制器。
 *
 * <p>治理提案通过后不立即执行，需经过固定延迟期（timelock），
 * 给社区留出审视与应对窗口。延迟到期后方可执行，期间可取消。</p>
 *
 * @since 1.2
 */
@Component
public class TimelockController {

    private static final Logger logger = LoggerFactory.getLogger(TimelockController.class);

    /** 默认延迟：2 天 */
    private static final java.time.Duration DEFAULT_DELAY = java.time.Duration.ofDays(2);

    private final java.time.Duration delay;
    private final Map<String, TimelockedOperation> queue = new ConcurrentHashMap<>();

    public TimelockController() {
        this(DEFAULT_DELAY);
    }

    public TimelockController(java.time.Duration delay) {
        this.delay = delay;
    }

    /**
     * 调度一个延迟操作。
     *
     * @param txId      操作 ID
     * @param operation 实际执行逻辑
     * @param now       调度时间
     */
    public void schedule(String txId, Runnable operation, Instant now) {
        if (txId == null || operation == null) {
            return;
        }
        Instant eta = now.plus(delay);
        queue.put(txId, new TimelockedOperation(operation, eta));
        logger.info("Timelock scheduled {} eta={}", txId, eta);
    }

    /**
     * 在时间锁到期后执行操作。
     *
     * @param txId 操作 ID
     * @param now  当前时间
     * @return 执行成功返回 true；未找到 / 未到期 / 执行异常返回 false
     */
    public boolean execute(String txId, Instant now) {
        TimelockedOperation op = queue.get(txId);
        if (op == null) {
            logger.warn("Timelock execute failed: tx not found {}", txId);
            return false;
        }
        if (now.isBefore(op.eta)) {
            logger.warn("Timelock execute failed: not yet mature for {} (eta={})", txId, op.eta);
            return false;
        }
        try {
            op.operation.run();
            queue.remove(txId);
            logger.info("Timelock executed {}", txId);
            return true;
        } catch (Exception e) {
            logger.error("Timelock execution failed for {}", txId, e);
            return false;
        }
    }

    /**
     * 取消已调度但未执行的操作。
     *
     * @param txId 操作 ID
     * @return 取消成功返回 true；不存在返回 false
     */
    public boolean cancel(String txId) {
        TimelockedOperation removed = queue.remove(txId);
        if (removed != null) {
            logger.info("Timelock cancelled {}", txId);
            return true;
        }
        return false;
    }

    /**
     * 查询操作的预计到期时间。
     *
     * @param txId 操作 ID
     * @return 到期时间；不存在返回 null
     */
    public Instant getEta(String txId) {
        TimelockedOperation op = queue.get(txId);
        return op == null ? null : op.eta;
    }

    public java.time.Duration getDelay() {
        return delay;
    }

    public int getQueuedCount() {
        return queue.size();
    }

    /** 时间锁操作条目 */
    private static final class TimelockedOperation {
        final Runnable operation;
        final Instant eta;

        TimelockedOperation(Runnable operation, Instant eta) {
            this.operation = operation;
            this.eta = eta;
        }
    }
}
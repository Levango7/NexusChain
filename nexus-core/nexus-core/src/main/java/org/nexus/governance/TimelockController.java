package org.nexus.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 时间锁控制器。
 *
 * <p>治理提案通过后不立即执行，需经过固定延迟期（timelock），
 * 给社区留出审视与应对窗口。延迟到期后方可执行，期间可取消。</p>
 *
 * <h3>分级延迟</h3>
 * <p>根据参数敏感度选择不同延迟长度：</p>
 * <table>
 *   <caption>表：敏感度与 timelock 延迟对照表</caption>
 *   <tr><th>sensitivity</th><th>delay</th></tr>
 *   <tr><td>LOW</td><td>1 天</td></tr>
 *   <tr><td>MEDIUM</td><td>2 天</td></tr>
 *   <tr><td>HIGH</td><td>7 天</td></tr>
 *   <tr><td>修改 gov.timelockDelay 本身</td><td>14 天（强制）</td></tr>
 * </table>
 *
 * @since 1.2
 */
@Component
public class TimelockController {

    private static final Logger logger = LoggerFactory.getLogger(TimelockController.class);

    /** 默认延迟：2 天（MEDIUM 敏感度） */
    private static final Duration DEFAULT_DELAY = Duration.ofDays(2);
    /** LOW 敏感度延迟：1 天 */
    private static final Duration DELAY_LOW = Duration.ofDays(1);
    /** MEDIUM 敏感度延迟：2 天 */
    private static final Duration DELAY_MEDIUM = Duration.ofDays(2);
    /** HIGH 敏感度延迟：7 天 */
    private static final Duration DELAY_HIGH = Duration.ofDays(7);
    /** 修改 gov.timelockDelay 本身的强制延迟：14 天 */
    public static final Duration DELAY_TIMELOCK_CHANGE = Duration.ofDays(14);

    /** 参数名：时间锁延迟（修改该参数本身需强制 14d） */
    public static final String TIMELOCK_DELAY_PARAM = "gov.timelockDelay";

    private final Duration delay;
    private final Map<String, TimelockedOperation> queue = new ConcurrentHashMap<>();

    public TimelockController() {
        this(DEFAULT_DELAY);
    }

    public TimelockController(Duration delay) {
        this.delay = delay;
    }

    /**
     * 调度一个延迟操作（使用默认延迟）。
     *
     * @param txId      操作 ID
     * @param operation 实际执行逻辑
     * @param now       调度时间
     */
    public void schedule(String txId, Runnable operation, Instant now) {
        schedule(txId, operation, now, delay);
    }

    /**
     * 调度一个延迟操作，按参数敏感度选择延迟。
     *
     * @param txId        操作 ID
     * @param operation   实际执行逻辑
     * @param now         调度时间
     * @param sensitivity 参数敏感度
     */
    public void schedule(String txId, Runnable operation, Instant now, ParameterSensitivity sensitivity) {
        schedule(txId, operation, now, delayFor(sensitivity));
    }

    /**
     * 调度一个延迟操作，使用指定延迟时长。
     *
     * @param txId      操作 ID
     * @param operation 实际执行逻辑
     * @param now       调度时间
     * @param customDelay 自定义延迟
     */
    public void schedule(String txId, Runnable operation, Instant now, Duration customDelay) {
        if (txId == null || operation == null) {
            return;
        }
        Duration effective = customDelay == null ? delay : customDelay;
        Instant eta = now.plus(effective);
        queue.put(txId, new TimelockedOperation(operation, eta));
        logger.info("Timelock scheduled {} eta={} delay={}", txId, eta, effective);
    }

    /**
     * 根据敏感度返回对应延迟时长。
     *
     * @param sensitivity 敏感度
     * @return 延迟时长
     */
    public Duration delayFor(ParameterSensitivity sensitivity) {
        if (sensitivity == null) {
            return delay;
        }
        switch (sensitivity) {
            case LOW:
                return DELAY_LOW;
            case MEDIUM:
                return DELAY_MEDIUM;
            case HIGH:
                return DELAY_HIGH;
            default:
                return delay;
        }
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
        } catch (RuntimeException e) {
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

    public Duration getDelay() {
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

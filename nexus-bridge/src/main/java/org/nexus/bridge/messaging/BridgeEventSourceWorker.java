package org.nexus.bridge.messaging;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 桥消息**事件源中继 worker**（PLAN-046）：消息链路的前段驱动。
 *
 * <p>定时轮询 {@link ChainEventSource} → {@link MessageFormatter#formatMessage}
 * 组装 → {@link MessageRelayer#relayMessage} 真实签名 + 入库。产出的是
 * {@link MessageStatus#RELAYED}（已中继、待执行）消息，随后由既有
 * {@link MessageRelayWorker} 消费（阈值验签 → 可选执行 → 记账）——
 * 本类不重复那段逻辑。</p>
 *
 * <h2>容错语义</h2>
 * <ul>
 *   <li><b>逐事件容错</b>：单条事件转换/中继失败（乱序、超时、存储抖动）
 *       只跳过该条并计数，不抛出、不打死循环——与 {@link MessageRelayWorker}
 *       的 findPending 容错同口径；</li>
 *   <li><b>poll 容错</b>：事件源本身抛异常时本轮返回 0，下一轮重试；
 *       {@link #lastError()} 暴露最近一次错误摘要（与
 *       {@code /api/v1/bridge/messaging} 的 eventSource 字段联动）。</li>
 * </ul>
 *
 * <h2>多副本</h2>
 * <p>{@code @SchedulerLock}（ShedLock）保证多副本只有一格执行——与
 * {@link MessageRelayWorker} 同纪律；事件源是进程内内存态（模拟实现），
 * 多副本下应由持有锁的那一格驱动。</p>
 *
 * @since 2.77.0
 */
public class BridgeEventSourceWorker {

    private static final Logger log = LoggerFactory.getLogger(BridgeEventSourceWorker.class);

    private final ChainEventSource eventSource;
    private final MessageFormatter formatter;
    private final MessageRelayer relayer;
    private final String relayerPrivateKey;

    /** 单轮最多拉取事件数（防一次拉爆；默认 1 = 模拟链每轮一条）。 */
    private final int batchSize;

    private final AtomicLong pollCount = new AtomicLong();
    private final AtomicLong relayedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong lastNonce = new AtomicLong(-1L);

    /** 最近一次错误摘要（无错误为 null）。 */
    private volatile String lastError;

    public BridgeEventSourceWorker(ChainEventSource eventSource,
                                   MessageFormatter formatter,
                                   MessageRelayer relayer,
                                   String relayerPrivateKey,
                                   int batchSize) {
        this.eventSource = java.util.Objects.requireNonNull(eventSource, "eventSource");
        this.formatter = java.util.Objects.requireNonNull(formatter, "formatter");
        this.relayer = java.util.Objects.requireNonNull(relayer, "relayer");
        this.relayerPrivateKey = java.util.Objects.requireNonNull(relayerPrivateKey, "relayerPrivateKey");
        this.batchSize = batchSize > 0 ? batchSize : 1;
    }

    /**
     * 一轮：拉事件 → 组装 → 签名入库。
     *
     * @return 本轮成功中继的事件条数
     */
    @Scheduled(fixedDelayString = "${nexus.bridge.messaging.event-source.poll-interval-ms:10000}")
    @SchedulerLock(name = "bridgeEventSourceWorker", lockAtMostFor = "PT2M", lockAtLeastFor = "PT2S")
    // PLAN-055：ShedLock 4.x 拒绝 primitive 返回（LockingNotSupportedException:
    // "Can not lock method returning primitive value"）——测试床实测：int 返回会让
    // AOP 在调用前抛异常，runOnce 永不执行（eventSource.pollCount 恒 0）。
    // 与 MessageRelayWorker 不同：那边是 void 包装 int，本题必须装箱 Integer。
    public Integer runOnce() {
        pollCount.incrementAndGet();
        List<ChainEvent> events;
        try {
            events = eventSource.pollEvents(batchSize);
        } catch (RuntimeException e) {
            lastError = "poll failed: " + e.getMessage();
            log.warn("event-source: poll failed: {}", e.getMessage());
            return 0;
        }
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int relayed = 0;
        for (ChainEvent event : events) {
            lastNonce.set(event.nonce());
            try {
                CrossChainMessage message = formatter.formatMessage(
                        event.sourceChain(), event.targetChain(),
                        event.sourceContract(), event.targetContract(),
                        event.payload(), event.nonce(), System.currentTimeMillis() / 1000L);
                relayer.relayMessage(message, relayerPrivateKey);
                relayed++;
                lastError = null;
                log.info("event-source: relayed {}#{} as message {}", event.sourceChain(),
                        event.nonce(), message.getMessageId());
            } catch (RuntimeException e) {
                failedCount.incrementAndGet();
                lastError = event.sourceChain() + "#" + event.nonce() + " -> " + e.getMessage();
                // 逐事件容错：单条失败不中断本轮其余事件（乱序/存储抖动时常见）
                log.warn("event-source: skip {}#{}: {}", event.sourceChain(), event.nonce(), e.getMessage());
            }
        }
        relayedCount.addAndGet(relayed);
        return relayed;
    }

    public long pollCount() {
        return pollCount.get();
    }

    public long relayedCount() {
        return relayedCount.get();
    }

    public long failedCount() {
        return failedCount.get();
    }

    /** 最近一次成功产出事件的 nonce（无为 -1）。 */
    public long lastNonce() {
        return lastNonce.get();
    }

    /** 最近一次错误摘要（无为 null）。 */
    public String lastError() {
        return lastError;
    }

    /**
     * 只读进度快照（供 {@code GET /api/v1/bridge/messaging} 每次请求实时构建）。
     *
     * <p>刻意不做成单例 Bean：快照必须是**请求时**的活值，启动时构造的记录
     * 会把计数冻死在 0（ PLAN-040/044 的"缓存 vs 快照"教训：观测面读活值）。</p>
     */
    public BridgeEventSourceStatus snapshot() {
        return new BridgeEventSourceStatus(true, eventSource.getSourceChain(),
                eventSource.getTargetChain(), eventSource.describe(),
                pollCount.get(), relayedCount.get(), failedCount.get(),
                lastNonce.get(), lastError);
    }
}

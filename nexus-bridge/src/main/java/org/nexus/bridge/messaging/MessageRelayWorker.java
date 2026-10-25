package org.nexus.bridge.messaging;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Optional;

/**
 * 消息中继循环（PLAN-037 切片 2）——把"装配"变成"流转"。
 *
 * <p>链路：{@code findPending} → 顺序消费（sourceChain, nonce）→ 阈值验签
 * （{@link MessageRelayer#verifySignatures}）→ 需要执行时
 * {@link MessageExecutor#executeMessage} 记账。单条失败只影响该条
 * （置 FAILED 留下轮重试），不阻断整轮循环——与
 * {@code PendingBlocksManager} 的既有失败隔离语义一致。</p>
 *
 * <h2>边界（勿夸大）</h2>
 * <ul>
 *   <li>只消费<b>已入库</b>消息的"后段"（验签/执行）；消息的"前段"
 *       （外部链事件 → 构造 CrossChainMessage → 落库）<b>不在本切片</b>，
 *       需要真实链适配器与事件源。</li>
 *   <li>{@code executeEnabled=false}（默认）时只做验签观察，不产真实交易。</li>
 *   <li>不引入新线程：{@code @Scheduled} 复用 Spring 调度线程；多实例部署时
 *       {@code @SchedulerLock}（桥既有调度任务同模式，见 {@code ShedLockConfig}）
 *       保证同一时刻仅一个实例执行。降级语义同既有：shedlock 表缺失时
 *       失去锁保护、调度照常（单轮上限 {@code PT2M}，超长单轮退化为可并发）。</li>
 * </ul>
 *
 * @since 2.76.0
 */
public class MessageRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(MessageRelayWorker.class);

    private final MessageStore store;
    private final MessageRelayer relayer;
    private final MessageExecutor executor;
    private final MessageConfig config;

    /** 单轮最多处理条数（防止积压时单轮过长）。 */
    private final int batchSize;

    /** 是否真正执行到目标链（默认 false：只验签观察）。 */
    private final boolean executeEnabled;

    public MessageRelayWorker(MessageStore store,
                              MessageRelayer relayer,
                              MessageExecutor executor,
                              MessageConfig config,
                              @Value("${nexus.bridge.messaging.batch-size:50}") int batchSize,
                              @Value("${nexus.bridge.messaging.execute-enabled:false}") boolean executeEnabled) {
        this.store = store;
        this.relayer = relayer;
        this.executor = executor;
        this.config = config;
        this.batchSize = batchSize > 0 ? batchSize : 50;
        this.executeEnabled = executeEnabled;
    }

    /**
     * 一轮中继处理。
     *
     * @return 本轮处理条数（含失败）
     */
    @Scheduled(fixedDelayString = "${nexus.bridge.messaging.poll-interval-ms:5000}")
    @SchedulerLock(name = "messageRelayWorker", lockAtMostFor = "PT2M", lockAtLeastFor = "PT2S")
    public int runOnce() {
        List<CrossChainMessage> pending;
        try {
            pending = store.findPending(batchSize);
        } catch (RuntimeException e) {
            log.warn("relay: findPending failed (storage outage?): {}", e.getMessage());
            return 0;
        }
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int handled = 0;
        for (CrossChainMessage message : pending) {
            handled++;
            handle(message);
        }
        return handled;
    }

    private void handle(CrossChainMessage message) {
        String id = message.getMessageId();
        try {
            // 1) 签名数阈值（不满足 → FAILED 留下轮；本切片不自动补签）
            if (message.signatureCount() < config.getRequiredSignatures()) {
                log.warn("relay: message {} below signature threshold (got={}, required={})",
                        id, message.signatureCount(), config.getRequiredSignatures());
                markFailed(message);
                return;
            }
            // 2) 逐签验签（validatorPublicKeys 为空 = 只做计数检查）
            if (!relayer.verifySignatures(message, config.getRequiredSignatures(), List.of())) {
                log.warn("relay: message {} failed signature verification", id);
                markFailed(message);
                return;
            }
            // 3) 执行开关关闭时只观察，不产真实交易
            if (!executeEnabled) {
                log.info("relay: message {} passed verification (execution deferred: "
                        + "execute-enabled=false)", id);
                return;
            }
            // 4) 执行 + 记账（executeMessage 内部会 recordExecution）
            String txHash = executor.executeMessage(message);
            log.info("relay: message {} executed, txHash={}", id, txHash);
        } catch (RuntimeException e) {
            log.warn("relay: message {} failed this round: {}", id, e.getMessage());
            markFailed(message);
        }
    }

    private void markFailed(CrossChainMessage message) {
        Optional<CrossChainMessage> current = store.findById(message.getMessageId());
        if (current.isPresent()) {
            current.get().setStatus(MessageStatus.FAILED);
        }
    }
}

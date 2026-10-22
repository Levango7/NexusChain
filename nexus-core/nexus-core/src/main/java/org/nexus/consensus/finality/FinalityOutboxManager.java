package org.nexus.consensus.finality;

import org.nexus.consensus.finality.persistence.FinalityOutboxEntry;
import org.nexus.consensus.finality.persistence.FinalityStateStore;
import org.nexus.consensus.finality.persistence.FinalityVoteOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 最终性投票的**持久化 outbox + 重试中枢**（PLAN-018 Phase 6C 第二切片）。
 *
 * <p>它补上 PLAN-016 留下的三个缺口：</p>
 * <ol>
 *   <li><b>持久化 outbox</b>：票写不进 {@code finality_votes} 时，先把"写库意图"
 *       （含完整签名）落进 {@code finality_vote_outbox} 表；进程重启后从库里捞回来继续补写，
 *       不再依赖"下一次出块"这种偶然时机。</li>
 *   <li><b>完整 Vote 签名</b>：outbox 与 {@code finality_votes} 都存 Ed25519 签名与公钥，
 *       恢复后能重新做聚合验签（否则重启后检查点永远停在"权重够了但验签不了"）。</li>
 *   <li><b>接收侧独立重试</b>：收到对端的票却写库失败时，<b>接收方自己</b>排队重试落库，
 *       不再把希望寄托在发送方重发上（旧行为：票直接蒸发，且发送方并不会重发）。</li>
 * </ol>
 *
 * <p>分层：</p>
 * <ul>
 *   <li><b>内存队列</b>：数据库整体不可用时的兜底（有界，溢出丢弃最旧并显式报错）；</li>
 *   <li><b>持久化 outbox</b>：数据库可用时的耐久层，跨进程重启存活。</li>
 * </ul>
 *
 * <p>两者同源：先入内存队列，再尽力落 outbox；补写成功后同时清理。
 * 数据库彻底不可用时只能靠内存队列 —— 这一点是本切片的诚实边界，
 * 详见 {@code docs/plan/PLAN-018-finality-persistence.md}。</p>
 *
 * <p>线程安全：所有状态变更都在本对象的 monitor 下；调用方
 * （协调器/广播器）只在"提交失败"之后才调用本类，不会与 flush 形成锁序反转。</p>
 */
@Component
public class FinalityOutboxManager {

    private static final Logger log = LoggerFactory.getLogger(FinalityOutboxManager.class);

    /** 每类（local/received）内存队列上限；溢出丢弃最旧并显式 ERROR。 */
    static final int MAX_PENDING_PER_ORIGIN = 256;

    /** 单轮从库里捞取的 outbox 行数上限。 */
    static final int DB_FETCH_BATCH = 64;

    /** 失败日志采样窗口（毫秒）：窗口内只打一条，避免故障期间刷屏。 */
    static final long LOG_SAMPLE_WINDOW_MS = 10_000L;

    private final FinalityGadget gadget;

    /** local：���节点自己签发的票；received：从对端收到的票。 */
    private final Map<String, PendingVote> localPending = new LinkedHashMap<>();
    private final Map<String, PendingVote> receivedPending = new LinkedHashMap<>();

    private final AtomicLong lastFlushAt = new AtomicLong(0);
    private final AtomicLong lastEnqueueAt = new AtomicLong(0);
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalFlushed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    /** 失败日志采样：窗口起点与窗口内被抑制的条数。 */
    private final AtomicLong logWindowStartedAt = new AtomicLong(0);
    private final AtomicLong logWindowSuppressed = new AtomicLong(0);

    /**
     * 本地票补写成功后的回调（由 {@link FinalityCoordinator} 注入，用于补发 P2P 广播）。
     *
     * <p>为什么需要：一张票在本节点写库失败时，发送方并没有把它发出去过
     * （广播发生在写库成功之后），补写成功后必须补发一次，否则对端永远收不到这张票。</p>
     */
    private volatile Consumer<Vote> localVotePersistedListener;

    public FinalityOutboxManager(FinalityGadget gadget) {
        this.gadget = gadget;
    }

    /** 注入"本地票补写成功后"的回调（补发广播）。 */
    public void setLocalVotePersistedListener(Consumer<Vote> listener) {
        this.localVotePersistedListener = listener;
    }

    /**
     * 入队一张**本地**票（发送侧：写库失败）。
     *
     * @param vote  待补写的完整投票（含签名与公钥）
     * @param error 触发入队的异常（可为 null）
     */
    public void enqueueLocal(Vote vote, Throwable error) {
        enqueue(vote, FinalityVoteOrigin.LOCAL, error);
    }

    /**
     * 入队一张**远端**票（接收侧：写库失败）。
     *
     * <p>这是 PLAN-018 的核心修复：旧行为里这张票会被直接丢弃，
     * 而发送方并不会重发（它只在自己写库失败时才重发），
     * 于是"对方库故障 ⇒ 这张票永远消失"。</p>
     */
    public void enqueueReceived(Vote vote, Throwable error) {
        enqueue(vote, FinalityVoteOrigin.RECEIVED, error);
    }

    private synchronized void enqueue(Vote vote, FinalityVoteOrigin origin, Throwable error) {
        if (vote == null) {
            return;
        }
        Map<String, PendingVote> queue = origin == FinalityVoteOrigin.LOCAL ? localPending : receivedPending;
        String key = pendingKey(vote, origin);
        if (queue.containsKey(key)) {
            // 同一张票重复入队：只更新错误信息，不重复占位。
            PendingVote existing = queue.get(key);
            existing.error = describe(error);
            return;
        }
        if (queue.size() >= MAX_PENDING_PER_ORIGIN) {
            PendingVote dropped = queue.values().iterator().next();
            queue.remove(pendingKey(dropped.vote, origin));
            log.error("Finality outbox queue full ({}); dropping oldest pending vote epoch={}, validator={}, origin={}",
                    MAX_PENDING_PER_ORIGIN, dropped.vote.getEpoch(),
                    dropped.vote.getValidatorAddress(), origin.code());
            totalFailed.incrementAndGet();
        }
        PendingVote pending = new PendingVote(vote, origin);
        pending.error = describe(error);
        queue.put(key, pending);
        totalEnqueued.incrementAndGet();
        lastEnqueueAt.set(System.currentTimeMillis());
        lastError.set(pending.error);
        if (shouldLogFailure()) {
            log.warn("Finality vote queued for retry: epoch={}, validator={}, origin={}, pending(local={}, received={}): {}",
                    vote.getEpoch(), vote.getValidatorAddress(), origin.code(),
                    localPending.size(), receivedPending.size(), pending.error);
        }
        // 尽力落持久化 outbox（数据库整体不可用时会失败，内存队列兜底）。
        persistToOutbox(pending);
    }

    /**
     * 补写重试（定时 + 区块事件触发）。
     *
     * <p>整段包在 try/catch 里：{@code @Scheduled} 抛异常会被默认处理器吞掉并打印，
     * 但更糟的是让本轮后续票全部得不到处理。</p>
     */
    @Scheduled(fixedDelay = 5_000L)
    public void scheduledFlush() {
        try {
            flush();
        } catch (RuntimeException e) {
            recordFailure(e);
            log.warn("Finality outbox flush failed; will retry on next tick: {}", e.getMessage());
        }
    }

    /**
     * 执行一轮补写：先处理内存队列（对象完整），再处理库里残留的行（重启恢复）。
     *
     * <p>公开可见以便测试与"新区块事件"触发即时补写。</p>
     */
    public synchronized void flush() {
        lastFlushAt.set(System.currentTimeMillis());
        // 1) 内存中的票
        for (PendingVote pending : new ArrayList<>(localPending.values())) {
            tryFlush(pending, localPending);
        }
        for (PendingVote pending : new ArrayList<>(receivedPending.values())) {
            tryFlush(pending, receivedPending);
        }
        // 2) 库里残留的行（进程重启后内存队列是空的，只能从这里恢复）
        try {
            FinalityStateStore store = gadget.getStateStore();
            if (store == null) {
                return;
            }
            List<FinalityOutboxEntry> rows = store.loadPendingVotes(DB_FETCH_BATCH);
            for (FinalityOutboxEntry row : rows) {
                if (isInMemory(row)) {
                    continue;   // 内存里已有同一张票，上面已处理过
                }
                tryFlushRow(row);
            }
        } catch (RuntimeException e) {
            recordFailure(e);
            log.warn("Finality outbox reload from database failed; will retry on next tick: {}", e.getMessage());
        }
    }

    private void tryFlush(PendingVote pending, Map<String, PendingVote> queue) {
        try {
            gadget.submitVote(pending.vote);
            queue.remove(pendingKey(pending.vote, pending.origin));
            removeFromOutbox(pending);
            totalFlushed.incrementAndGet();
            log.info("Finality pending vote persisted: epoch={}, validator={}, origin={}, finalized={}",
                    pending.vote.getEpoch(), pending.vote.getValidatorAddress(), pending.origin.code(),
                    gadget.isFinalized(pending.vote.getEpoch(), pending.vote.getCheckpointHash()));
            if (pending.origin == FinalityVoteOrigin.LOCAL) {
                Consumer<Vote> listener = localVotePersistedListener;
                if (listener != null) {
                    try {
                        listener.accept(pending.vote);
                    } catch (RuntimeException e) {
                        log.warn("Finality vote rebroadcast after retry failed: epoch={}, validator={}: {}",
                                pending.vote.getEpoch(), pending.vote.getValidatorAddress(), e.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) {
            pending.error = describe(e);
            totalFailed.incrementAndGet();
            recordFailure(e);
            persistToOutbox(pending);
            if (shouldLogFailure()) {
                log.warn("Finality pending vote still failing: epoch={}, validator={}, origin={}: {}",
                        pending.vote.getEpoch(), pending.vote.getValidatorAddress(), pending.origin.code(),
                        pending.error);
            }
        }
    }

    /** 补写一条来自持久化 outbox 的行（重启恢复路径）。 */
    private void tryFlushRow(FinalityOutboxEntry row) {
        Vote vote = row.toVote();
        try {
            gadget.submitVote(vote);
            removeFromOutbox(row);
            totalFlushed.incrementAndGet();
            log.info("Finality outbox row persisted after restart: epoch={}, validator={}, origin={}",
                    vote.getEpoch(), vote.getValidatorAddress(), row.getOrigin().code());
            if (row.getOrigin() == FinalityVoteOrigin.LOCAL) {
                Consumer<Vote> listener = localVotePersistedListener;
                if (listener != null) {
                    try {
                        listener.accept(vote);
                    } catch (RuntimeException e) {
                        log.warn("Finality vote rebroadcast after restart-recovery failed: {}", e.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) {
            totalFailed.incrementAndGet();
            recordFailure(e);
            markOutboxFailure(row, describe(e));
            if (shouldLogFailure()) {
                log.warn("Finality outbox row still failing: epoch={}, validator={}, origin={}: {}",
                        vote.getEpoch(), vote.getValidatorAddress(), row.getOrigin().code(), describe(e));
            }
        }
    }

    /** 尽力把待补写票落进持久化 outbox（数据库不可用时静默失败，内存队列兜底）。 */
    private void persistToOutbox(PendingVote pending) {
        FinalityStateStore store = gadget.getStateStore();
        if (store == null) {
            return;
        }
        try {
            if (!pending.persisted) {
                store.enqueuePendingVote(new FinalityOutboxEntry(
                        pending.vote.getEpoch(), pending.vote.getCheckpointHash(),
                        pending.vote.getValidatorAddress(), pending.vote.getSignature(),
                        pending.vote.getPublicKeyBytes(), pending.origin,
                        pending.createdAt, pending.attempts, pending.error));
                pending.persisted = true;
            } else {
                store.markPendingVoteFailure(toEntry(pending), pending.error);
                pending.attempts++;
            }
        } catch (RuntimeException e) {
            // 数据库整体不可用：outbox 也写不进去，内存队列是唯一兜底。
            pending.persisted = false;
        }
    }

    private void removeFromOutbox(PendingVote pending) {
        FinalityStateStore store = gadget.getStateStore();
        if (store == null) {
            return;
        }
        try {
            store.removePendingVote(toEntry(pending));
        } catch (RuntimeException e) {
            log.warn("Failed to clean finality outbox row: epoch={}, validator={}: {}",
                    pending.vote.getEpoch(), pending.vote.getValidatorAddress(), e.getMessage());
        }
    }

    private void removeFromOutbox(FinalityOutboxEntry row) {
        FinalityStateStore store = gadget.getStateStore();
        if (store == null) {
            return;
        }
        try {
            store.removePendingVote(row);
        } catch (RuntimeException e) {
            log.warn("Failed to clean finality outbox row: {}", e.getMessage());
        }
    }

    private void markOutboxFailure(FinalityOutboxEntry row, String error) {
        FinalityStateStore store = gadget.getStateStore();
        if (store == null) {
            return;
        }
        try {
            store.markPendingVoteFailure(row, error);
        } catch (RuntimeException e) {
            // 忽略：失败计数只是观测信息
        }
    }

    private boolean isInMemory(FinalityOutboxEntry row) {
        String localKey = pendingKey(row.getEpoch(), row.getCheckpointHash(),
                row.getValidatorAddress(), FinalityVoteOrigin.LOCAL);
        String receivedKey = pendingKey(row.getEpoch(), row.getCheckpointHash(),
                row.getValidatorAddress(), FinalityVoteOrigin.RECEIVED);
        return localPending.containsKey(localKey) || receivedPending.containsKey(receivedKey);
    }

    /** 当前观测快照。 */
    public synchronized FinalityOutboxStatus status() {
        int dbPending = 0;
        try {
            FinalityStateStore store = gadget.getStateStore();
            if (store != null) {
                dbPending = store.loadPendingVotes(DB_FETCH_BATCH).size();
            }
        } catch (RuntimeException e) {
            // 数据库不可用时读不到行数，内存计数仍然有效。
        }
        return new FinalityOutboxStatus(localPending.size(), receivedPending.size(), dbPending,
                lastFlushAt.get(), lastEnqueueAt.get(),
                totalEnqueued.get(), totalFlushed.get(), totalFailed.get(), lastError.get());
    }

    /** 内存中待补写的票数（测试与观测用）。 */
    public synchronized int pendingCount(FinalityVoteOrigin origin) {
        return origin == FinalityVoteOrigin.LOCAL ? localPending.size() : receivedPending.size();
    }

    /** 内存中待补写的票总数（测试与观测用）。 */
    public synchronized int pendingCount() {
        return localPending.size() + receivedPending.size();
    }

    private void recordFailure(Throwable error) {
        lastError.set(describe(error));
    }

    /**
     * 失败日志采样：10 秒窗口内只允许一条，其余计数后并入下一条。
     *
     * <p>为什么需要：数据库整体不可用时，每张票每轮补写都会失败一次 ——
     * 不采样的话，30 秒故障能刷出几百条 WARN/ERROR，把真正的故障信号淹没
     * （Phase 6C 第一切片对 {@code AccountDB}/{@code Propose paused} 用了同样的口径）。</p>
     *
     * @return true 表示本次应当打印
     */
    private boolean shouldLogFailure() {
        long now = System.currentTimeMillis();
        long start = logWindowStartedAt.get();
        if (start == 0 || now - start >= LOG_SAMPLE_WINDOW_MS) {
            logWindowStartedAt.set(now);
            long suppressed = logWindowSuppressed.getAndSet(0);
            if (suppressed > 0) {
                log.info("Finality outbox failure log: {} suppressed in the last {}s",
                        suppressed, LOG_SAMPLE_WINDOW_MS / 1000);
            }
            return true;
        }
        logWindowSuppressed.incrementAndGet();
        return false;
    }

    private static FinalityOutboxEntry toEntry(PendingVote pending) {
        return new FinalityOutboxEntry(
                pending.vote.getEpoch(), pending.vote.getCheckpointHash(),
                pending.vote.getValidatorAddress(), pending.vote.getSignature(),
                pending.vote.getPublicKeyBytes(), pending.origin,
                pending.createdAt, pending.attempts, pending.error);
    }

    private static String pendingKey(Vote vote, FinalityVoteOrigin origin) {
        return pendingKey(vote.getEpoch(), vote.getCheckpointHash(),
                vote.getValidatorAddress(), origin);
    }

    private static String pendingKey(long epoch, byte[] checkpointHash, String validator, FinalityVoteOrigin origin) {
        return epoch + "|" + hex(checkpointHash) + "|" + validator + "|" + origin.code();
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage());
        message = message.replace('\r', ' ').replace('\n', ' ');
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    /** 内存队列中的一条待补写票。 */
    private static final class PendingVote {
        private final Vote vote;
        private final FinalityVoteOrigin origin;
        private final long createdAt = System.currentTimeMillis();
        private int attempts;
        private boolean persisted;
        private String error = "";

        PendingVote(Vote vote, FinalityVoteOrigin origin) {
            this.vote = vote;
            this.origin = origin;
        }
    }
}

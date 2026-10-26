package org.nexus.sync;

import com.google.protobuf.AbstractMessage;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.account.Transaction;
import org.nexus.p2p.*;
import org.nexus.service.CommandService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
public class TransactionHandler implements Plugin {
    private PeerServer server;
    private static final int CACHE_SIZE = 256;
    private ConcurrentMap<String, Boolean> transactionCache;

    private static final Logger logger = LoggerFactory.getLogger(TransactionHandler.class);
    @Autowired
    private CommandService commandService;

    /**
     * 本地事务合批窗口（毫秒）。{@code <= 0} 表示关闭合批，逐笔立即广播。
     *
     * <p>P2P 批量化（2026-10-27）：本地受理的每笔交易原本各自触发一次
     * {@link PeerServer#broadcast}，即每笔 × 每个对端一次 gRPC 调用（含签名、
     * protobuf 编码、channel 复用的寻址与 future 调度）。高吞吐下这笔开销
     * 与交易数线性相关，而协议本身支持一个 {@code Transactions} 消息携带多笔
     * （{@link Util#split} 会按单包上限再切分）。这里用一个很短的窗口把并发
     * 到达的多笔合成一批，单笔延迟代价仅为窗口长度。</p>
     */
    @Value("${p2p.broadcast-batch-window-ms:20}")
    private int batchWindowMs = 20;

    /** 合批上限：待发事务达到该数量即立即冲刷，不等窗口到期（防止窗口内积压无界）。 */
    @Value("${p2p.broadcast-batch-max-transactions:1000}")
    private int batchMaxTransactions = 1000;

    /** 待广播事务队列（FIFO，保证同一发送方的事务顺序不变）。 */
    private final Queue<NexusChainOuterClass.Transaction> pendingTransactions = new ConcurrentLinkedQueue<>();

    /** 单飞标志：同一时刻只排一次窗口冲刷任务，避免每次 offer 都建定时任务。 */
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    /** 已排队的窗口冲刷任务；达到合批上限时需要它来提前冲刷。 */
    private volatile ScheduledFuture<?> windowFlush;

    private volatile ScheduledExecutorService flushExecutor;

    public TransactionHandler() {
        this.transactionCache = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(CACHE_SIZE).build();
    }

    @Override
    public void onMessage(Context context, PeerServer server) {
        if (context.getPayload().getCode() != NexusChainOuterClass.Code.TRANSACTIONS) {
            return;
        }
        NexusChainOuterClass.Transactions txs = (NexusChainOuterClass.Transactions) context.getPayload().getBody();
        String key = Hex.encodeHexString(Utils.getTransactionsHash(txs.getTransactionsList()));
        if (transactionCache.containsKey(key)) {
            return;
        }
        transactionCache.put(key, true);
        txs.getTransactionsList()
                .stream()
                .map(Utils::parseTransaction)
                .forEach(t -> {
                    transactionCache.put(t.getHashHexString(), true);
                    byte[] traninfo = t.toRPCBytes();
                    APIResult apiResult = commandService.verifyTransfer(traninfo);
                    if (apiResult.getCode() == 5000) {
                        logger.info("transaction Check failure,TxHash=" + Hex.encodeHexString(t.getHash()) + ",message:" + apiResult.getMessage());
                    }
                });
        context.relay();
    }

    @Override
    public void onStart(PeerServer server) {
        this.server = server;
    }

    /**
     * 广播本地受理的事务。
     *
     * <p>合批开关 {@code p2p.broadcast-batch-window-ms}：{@code > 0} 时先入队，
     * 由窗口到期或达到 {@code p2p.broadcast-batch-max-transactions} 触发一次冲刷，
     * 把窗口内的事务合并成尽量少的 {@code Transactions} 消息；{@code <= 0} 时为
     * 旧的逐笔立即广播语义（便于排查与测试）。</p>
     */
    public void broadcastTransactions(List<Transaction> txs) {
        if (server == null || txs == null || txs.isEmpty()) {
            return;
        }

        List<NexusChainOuterClass.Transaction> encoded = txs.stream()
                .map(Utils::encodeTransaction).collect(Collectors.toList());

        if (batchWindowMs <= 0) {
            dispatch(encoded);
            return;
        }

        pendingTransactions.addAll(encoded);
        scheduleFlush(pendingTransactions.size() >= batchMaxTransactions ? 0L : batchWindowMs);
    }

    /**
     * 排一次冲刷任务。{@code delayMs > 0} 走窗口路径（单飞，只在没有排队任务时新建）；
     * {@code delayMs == 0} 表示已达合批上限，取消排队中的窗口任务并立即冲刷。
     */
    private void scheduleFlush(long delayMs) {
        if (delayMs <= 0) {
            ScheduledFuture<?> pending = windowFlush;
            if (pending != null && pending.cancel(false)) {
                flushScheduled.set(false);
            }
            if (flushScheduled.compareAndSet(false, true)) {
                try {
                    executor().execute(this::flushPending);
                } catch (RejectedExecutionException e) {
                    flushScheduled.set(false);
                    flushPending();
                }
            }
            return;
        }
        if (flushScheduled.compareAndSet(false, true)) {
            try {
                windowFlush = executor().schedule(this::flushPending, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                flushScheduled.set(false);
                flushPending();
            }
        }
    }

    /** 取出全部待发事务并广播；执行期间新到的事务会重新排一次冲刷。 */
    private void flushPending() {
        flushScheduled.set(false);
        windowFlush = null;
        List<NexusChainOuterClass.Transaction> batch = new ArrayList<>();
        NexusChainOuterClass.Transaction tx;
        while ((tx = pendingTransactions.poll()) != null) {
            batch.add(tx);
        }
        if (!batch.isEmpty()) {
            dispatch(batch);
        }
    }

    /** 编码成 {@code Transactions} 消息 → 按单包上限切分 → 去重（命中缓存则不再发）→ 广播。 */
    private void dispatch(List<NexusChainOuterClass.Transaction> encoded) {
        PeerServer target = server;
        if (target == null || encoded.isEmpty()) {
            return;
        }
        // 合批后同一笔事务可能在窗口内被提交多次（重试/重复投递）。旧实现逐笔广播时
        // 靠消息哈希天然抑制，合并成一批后会把重复项一起带上——这里先按 proto 值
        // 相等折叠，保持「同一事务只上线一次」的原有语义。
        List<NexusChainOuterClass.Transaction> unique =
                new ArrayList<>(new LinkedHashSet<>(encoded));

        NexusChainOuterClass.Transactions msg = NexusChainOuterClass.Transactions.newBuilder()
                .addAllTransactions(unique).build();

        List<NexusChainOuterClass.Transactions> divided = Util.split(msg);

        divided.stream()
                .filter(o -> {
                    String k = Hex.encodeHexString(
                            Utils.getTransactionsHash(o.getTransactionsList())
                    );
                    if (!transactionCache.containsKey(k)) {
                        transactionCache.put(k, true);
                        return true;
                    }
                    return false;
                })
                .forEach(target::broadcast);
    }

    private ScheduledExecutorService executor() {
        ScheduledExecutorService ex = flushExecutor;
        if (ex == null) {
            synchronized (this) {
                ex = flushExecutor;
                if (ex == null) {
                    ex = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "p2p-tx-broadcast-flush");
                        t.setDaemon(true);
                        return t;
                    });
                    flushExecutor = ex;
                }
            }
        }
        return ex;
    }

    /** 停机时把窗口内未到期的事务同步发出去，避免静默丢弃。 */
    @PreDestroy
    public void shutdown() {
        flushPending();
        ScheduledExecutorService ex = flushExecutor;
        if (ex != null) {
            ex.shutdown();
        }
    }
}


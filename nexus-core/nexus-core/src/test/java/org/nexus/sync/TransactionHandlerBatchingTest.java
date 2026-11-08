package org.nexus.sync;

import com.google.protobuf.AbstractMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.core.account.Transaction;
import org.nexus.p2p.NexusChainOuterClass;
import org.nexus.p2p.PeerServer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P2P 事务合批（2026-10-27）：本地受理的事务在短窗口内合并成尽量少的
 * {@code Transactions} 消息，减少「每笔 × 每个对端」的 gRPC 广播次数。
 *
 * <p><b>时序确定性（2026-11-07）</b>：本类原实现用 80ms / 300ms / 60ms 的短窗口 +
 * 背靠背调用，并把「窗口到期后还没广播」这类断言直接放在调用之后。三处前提都不成立：
 * ① 背靠背调用可能跨过窗口边界 ⇒ 多次冲刷（原用例实测红：{@code Expected size: 1 but
 * was: 2}）；② 调用与断言之间线程只要被调度走超过窗口时长，断言就红（原用例实测红：
 * {@code [窗口未到期前不应广播] Expecting empty but was: [...]}）；③ 两处
 * {@code Thread.sleep(...)}「给可能的多余广播留出暴露时间」是掩盖式写法。
 *
 * <p>现改为：窗口放大到 2000ms（等待上界同步放大，避免「等窗口到期」本身变成临界竞态），
 * 折叠用例的冲刷由<b>合批上限（计数）</b>确定性触发 ⇒ 两笔必然同批，
 * {@code dispatch()} 的批内内容折叠成为承重路径；「恰好一次广播」改用
 * {@code verify(after(...).times(1))} 精确断言，不再靠 sleep 等待。
 */
class TransactionHandlerBatchingTest {

    private TransactionHandler handler;
    private PeerServer peerServer;
    private final List<AbstractMessage> broadcasts = new CopyOnWriteArrayList<>();
    private CountDownLatch latch;

    @BeforeEach
    void setUp() {
        handler = new TransactionHandler(256);
        peerServer = mock(PeerServer.class);
        latch = new CountDownLatch(1);
        doAnswer(inv -> {
            // 先写列表、再放闸 ⇒ latch 返回时 broadcasts 必定已含本次广播。
            broadcasts.add(inv.getArgument(0));
            latch.countDown();
            return null;
        }).when(peerServer).broadcast(any());
        handler.onStart(peerServer);
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
    }

    private static Transaction tx(long nonce, long amount) {
        return new Transaction(1, Transaction.Type.TRANSFER.ordinal(), nonce,
                new byte[Transaction.PUBLIC_KEY_SIZE], 1L, amount, new byte[0],
                new byte[Transaction.PUBLIC_KEY_HASH_SIZE], new byte[Transaction.SIGNATURE_SIZE]);
    }

    private void configureWindow(int windowMs, int maxTransactions) {
        ReflectionTestUtils.setField(handler, "batchWindowMs", windowMs);
        ReflectionTestUtils.setField(handler, "batchMaxTransactions", maxTransactions);
    }

    private List<NexusChainOuterClass.Transaction> transactionsOf(AbstractMessage msg) {
        assertThat(msg).isInstanceOf(NexusChainOuterClass.Transactions.class);
        return ((NexusChainOuterClass.Transactions) msg).getTransactionsList();
    }

    @Test
    @DisplayName("窗口内的多笔事务合并为一次广播")
    void coalescesTransactionsWithinWindow() throws Exception {
        // 窗口 80ms → 2000ms：三次调用是相邻语句，2s 窗口足以排除「调用之间恰好跨过
        // 窗口边界」造成的两次冲刷（原 80ms 实测踩到：Expected size: 1 but was: 2）。
        configureWindow(2000, 1000);

        handler.broadcastTransactions(Collections.singletonList(tx(1, 100)));
        handler.broadcastTransactions(Collections.singletonList(tx(2, 200)));
        handler.broadcastTransactions(Collections.singletonList(tx(3, 300)));

        // 等待上界必须显著大于窗口（2000ms），否则「等窗口到期」本身就成了临界竞态。
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("窗口到期后应发生一次广播").isTrue();
        verify(peerServer, after(300).times(1)).broadcast(any());
        assertThat(broadcasts).as("三笔应合并为一次广播").hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(3);
    }

    @Test
    @DisplayName("单笔事务不立即广播，等窗口到期（延迟上限即窗口）")
    void singleTransactionWaitsForWindow() throws Exception {
        // 窗口 300ms → 2000ms：原来「调用后紧接着断言尚未广播」在 300ms 窗口下，
        // 线程只要被调度走 >300ms 就会红（实测：[窗口未到期前不应广播] Expecting empty
        // but was: [...]）。窗口是排定时刻（t_call + delayMs），放大后该断言稳定。
        configureWindow(2000, 1000);

        handler.broadcastTransactions(Collections.singletonList(tx(1, 100)));

        assertThat(broadcasts).as("窗口未到期前不应广播").isEmpty();
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("窗口到期后应广播").isTrue();
        verify(peerServer, after(300).times(1)).broadcast(any());
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(1);
    }

    @Test
    @DisplayName("窗口 <= 0 时保持逐笔立即广播（可回退语义）")
    void zeroWindowBroadcastsImmediately() {
        // batchWindowMs <= 0 时生产侧走 dispatch(encoded) 同步分支（不排队、不起定时器）
        // ⇒ 本用例不依赖任何定时器时序，保持原样。
        configureWindow(0, 1000);

        handler.broadcastTransactions(Collections.singletonList(tx(1, 100)));
        handler.broadcastTransactions(Collections.singletonList(tx(2, 200)));

        assertThat(broadcasts).hasSize(2);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(1);
        assertThat(transactionsOf(broadcasts.get(1))).hasSize(1);
    }

    @Test
    @DisplayName("达到合批上限时立即冲刷，不等窗口")
    void maxBatchSizeFlushesImmediately() throws Exception {
        configureWindow(5000, 2);

        handler.broadcastTransactions(Collections.singletonList(tx(1, 100)));
        assertThat(broadcasts).as("未达上限不应提前广播").isEmpty();

        handler.broadcastTransactions(Collections.singletonList(tx(2, 200)));

        assertThat(latch.await(10, TimeUnit.SECONDS)).as("达到上限应立即冲刷").isTrue();
        verify(peerServer, after(300).times(1)).broadcast(any());
        assertThat(broadcasts).hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(2);
    }

    @Test
    @DisplayName("重复事务在合批后仍按消息哈希去重，只广播一次")
    void duplicateTransactionsAreDeduplicated() throws Exception {
        // 冲刷由「合批上限 = 2」这一**计数**条件确定性触发，而不是定时器：
        // 两笔必然落在**同一批**里 ⇒ dispatch() 的批内内容折叠（LinkedHashSet）
        // 成为承重路径。
        //
        // 原实现用 60ms 窗口 + 背靠背两次，窗口跨期后两笔落进**两次不同的冲刷**，
        // 用例靠「跨冲刷的消息哈希缓存」变绿 ⇒ 批内折叠路径根本没被覆盖
        // （实测：临时把 LinkedHashSet 换成 ArrayList，原用例仍然绿）。
        configureWindow(2000, 2);

        // 同内容、不同源实例：生产里的重试/重复投递会重新构造 Transaction。
        // 如实说明：这不是为了让「内容去重 vs 身份去重」可区分——编码侧
        // Utils.encodeTransaction 每次都新建 protobuf 实例，本就天然可区分；
        // 这里是对齐生产的真实形状，并防止未来 encodeTransaction 被改成缓存复用而退化。
        Transaction first = tx(1, 100);
        Transaction second = tx(1, 100);

        handler.broadcastTransactions(Collections.singletonList(first));
        handler.broadcastTransactions(Collections.singletonList(second));

        assertThat(latch.await(10, TimeUnit.SECONDS)).as("达到合批上限应发生一次广播").isTrue();
        verify(peerServer, after(300).times(1)).broadcast(any());
        assertThat(broadcasts).as("同一事务不应重复广播").hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).as("同一事务不应重复广播").hasSize(1);
    }
}

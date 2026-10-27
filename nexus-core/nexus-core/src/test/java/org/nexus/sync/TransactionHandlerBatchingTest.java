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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * P2P 事务合批（2026-10-27）：本地受理的事务在短窗口内合并成尽量少的
 * {@code Transactions} 消息，减少「每笔 × 每个对端」的 gRPC 广播次数。
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
        configureWindow(80, 1000);

        handler.broadcastTransactions(Collections.singletonList(tx(1, 100)));
        handler.broadcastTransactions(Collections.singletonList(tx(2, 200)));
        handler.broadcastTransactions(Collections.singletonList(tx(3, 300)));

        assertThat(latch.await(2, TimeUnit.SECONDS)).as("窗口到期后应发生一次广播").isTrue();
        Thread.sleep(150); // 给可能的多余广播留出暴露时间
        assertThat(broadcasts).as("三笔应合并为一次广播").hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(3);
    }

    @Test
    @DisplayName("单笔事务不立即广播，等窗口到期（延迟上限即窗口）")
    void singleTransactionWaitsForWindow() throws Exception {
        configureWindow(300, 1000);

        handler.broadcastTransactions(Collections.singletonList(tx(1, 100)));

        assertThat(broadcasts).as("窗口未到期前不应广播").isEmpty();
        assertThat(latch.await(3, TimeUnit.SECONDS)).as("窗口到期后应广播").isTrue();
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(1);
    }

    @Test
    @DisplayName("窗口 <= 0 时保持逐笔立即广播（可回退语义）")
    void zeroWindowBroadcastsImmediately() {
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

        assertThat(latch.await(2, TimeUnit.SECONDS)).as("达到上限应立即冲刷").isTrue();
        assertThat(broadcasts).hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(2);
    }

    @Test
    @DisplayName("重复事务在合批后仍按消息哈希去重，只广播一次")
    void duplicateTransactionsAreDeduplicated() throws Exception {
        configureWindow(60, 1000);

        Transaction duplicate = tx(1, 100);
        handler.broadcastTransactions(Collections.singletonList(duplicate));
        handler.broadcastTransactions(Collections.singletonList(duplicate));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(120);
        assertThat(broadcasts).as("同一事务不应重复广播").hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(1);
    }
}

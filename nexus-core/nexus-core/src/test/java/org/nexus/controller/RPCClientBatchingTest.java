package org.nexus.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.core.account.Transaction;
import org.nexus.encoding.JSONEncodeDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * REST 形态（{@code p2p.mode=rest}）的 P2P 广播合批。
 *
 * <p>补齐 2026-10-27 合批改动遗留的口子：此前只有 gRPC 形态
 * （{@code TransactionHandler}）做窗口合并，rest 形态仍然是「每笔 × 每个 peer」
 * 一次 HTTP POST。接收端 {@code /consensus/transactions} 本来就按列表遍历校验，
 * 一个 {@code Packet} 带多笔是协议支持的。</p>
 *
 * <p><b>时序确定性（2026-11-07）</b>：本类原实现用 80ms 窗口 + 背靠背两次调用，
 * 并在 {@code verify(timeout(...))} 返回后立刻读广播列表。两处时序前提都不成立：
 * ① 两次调用可能跨过窗口边界 ⇒ 两次 flush；② Mockito 在 answer 体执行前就登记
 * invocation ⇒ {@code verify} 返回时列表仍为空 ⇒ {@code ArrayIndexOutOfBoundsException}
 * （2026-11-06 CI 红 run 41997420158）。现改为：等待用信号量（answer 体内计数），
 * 窗口放大到 2000ms，折叠用例的冲刷由合批上限（计数）确定性触发。</p>
 */
class RPCClientBatchingTest {

    private RPCClient client;
    private JSONEncodeDecoder codec;
    private final List<byte[]> broadcasts = new CopyOnWriteArrayList<>();

    /**
     * 广播信号量：在 {@code doAnswer} 体内计数 ⇒ {@link #awaitBroadcast} 返回时，
     * 广播记录必定已写进 {@link #broadcasts}。
     *
     * <p>为什么不用 Mockito 的 {@code verify(timeout(...))} 等待：实测 Mockito 会在
     * answer 体执行完之前就登记 invocation，{@code verify} 返回时列表仍可能为空，
     * 紧接着读 {@code broadcasts.get(0)} 就会抛
     * {@code ArrayIndexOutOfBoundsException}。本地探针实测输出
     * {@code PROBE_VERIFY_RETURNED_BEFORE_ANSWER_BODY=true}。</p>
     */
    private CountDownLatch broadcastLatch;

    @BeforeEach
    void setUp() {
        codec = new JSONEncodeDecoder();
        client = spy(new RPCClient());
        ReflectionTestUtils.setField(client, "codec", codec);
        broadcastLatch = new CountDownLatch(1);
        doAnswer(inv -> {
            broadcasts.add(inv.getArgument(1));
            broadcastLatch.countDown();
            return null;
        }).when(client).broadcast(anyString(), any());
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
    }

    /** 等待第一次广播落地；返回 false 表示超时。 */
    private boolean awaitBroadcast(long timeoutMs) throws InterruptedException {
        return broadcastLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static Transaction tx(long nonce, long amount) {
        return new Transaction(1, Transaction.Type.TRANSFER.ordinal(), nonce,
                new byte[Transaction.PUBLIC_KEY_SIZE], 1L, amount, new byte[0],
                new byte[Transaction.PUBLIC_KEY_HASH_SIZE], new byte[Transaction.SIGNATURE_SIZE]);
    }

    private void configureWindow(int windowMs, int maxTransactions) {
        ReflectionTestUtils.setField(client, "batchWindowMs", windowMs);
        ReflectionTestUtils.setField(client, "batchMaxTransactions", maxTransactions);
    }

    private List<Transaction> transactionsOf(byte[] packetBytes) {
        Packet packet = codec.decode(packetBytes, Packet.class);
        assertThat(packet).isNotNull();
        return codec.decodeTransactions(packet.data);
    }

    @Test
    @DisplayName("窗口内的多笔交易合并为一次 HTTP 广播")
    void coalescesTransactionsWithinWindow() throws Exception {
        // 窗口 80ms → 2000ms：三次调用是相邻语句，2s 窗口足以排除「调用之间恰好跨过
        // 窗口边界」造成的两次 flush（原 80ms 在高负载 runner 上会踩到）。
        configureWindow(2000, 1000);

        client.broadcastTransactions(Collections.singletonList(tx(1, 100)));
        client.broadcastTransactions(Collections.singletonList(tx(2, 200)));
        client.broadcastTransactions(Collections.singletonList(tx(3, 300)));

        assertThat(awaitBroadcast(8000)).as("窗口到期后应发生一次广播").isTrue();
        verify(client, after(300).times(1)).broadcast(eq("/consensus/transactions"), any());
        assertThat(broadcasts).hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(3);
    }

    @Test
    @DisplayName("单笔事务等窗口到期后才广播（延迟上限即窗口）")
    void singleTransactionWaitsForWindow() throws Exception {
        // 窗口取大一点（2s），避免并行测试下"入队→断言"之间就到期
        configureWindow(2000, 1000);

        client.broadcastTransactions(Collections.singletonList(tx(1, 100)));

        assertThat(broadcasts).as("窗口未到期前不应广播").isEmpty();
        assertThat(awaitBroadcast(8000)).as("窗口到期后应广播").isTrue();
        verify(client, after(300).times(1)).broadcast(eq("/consensus/transactions"), any());
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(1);
    }

    @Test
    @DisplayName("窗口 <= 0 时保持逐笔立即广播（可回退语义）")
    void zeroWindowBroadcastsImmediately() {
        configureWindow(0, 1000);

        client.broadcastTransactions(Collections.singletonList(tx(1, 100)));
        client.broadcastTransactions(Collections.singletonList(tx(2, 200)));

        assertThat(broadcasts).hasSize(2);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(1);
        assertThat(transactionsOf(broadcasts.get(1))).hasSize(1);
    }

    @Test
    @DisplayName("达到合批上限时立即冲刷，不等窗口")
    void maxBatchSizeFlushesImmediately() throws Exception {
        configureWindow(5000, 2);

        client.broadcastTransactions(Collections.singletonList(tx(1, 100)));
        assertThat(broadcasts).as("未达上限不应提前广播").isEmpty();

        client.broadcastTransactions(Collections.singletonList(tx(2, 200)));

        assertThat(awaitBroadcast(5000)).as("达到上限应立即冲刷").isTrue();
        verify(client, after(300).times(1)).broadcast(eq("/consensus/transactions"), any());
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(2);
    }

    @Test
    @DisplayName("重复交易在合批后按交易哈希折叠，只广播一次")
    void duplicateTransactionsAreDeduplicated() throws Exception {
        // 冲刷由「合批上限 = 2」这一**计数**条件确定性触发，而不是定时器：
        // 两次调用必然落在同一批里 ⇒ 不再依赖「两次调用恰好落在同一个 80ms 窗口内」
        // 这个时序前提（原 CI 红 run 41997420158 的直接成因之一）。
        configureWindow(2000, 2);

        // 两个**内容相同、实例不同**的交易：生产里的重试/重复投递是重新构造对象，
        // 若两次传的是同一实例，任何「按对象身份去重」的实现也会通过 ⇒ 盖不住回归。
        Transaction first = tx(1, 100);
        Transaction second = tx(1, 100);
        assertThat(second.getHash()).as("同内容交易应同哈希").isEqualTo(first.getHash());

        client.broadcastTransactions(Collections.singletonList(first));
        client.broadcastTransactions(Collections.singletonList(second));

        assertThat(awaitBroadcast(5000)).as("达到合批上限应发生一次广播").isTrue();
        verify(client, after(300).times(1)).broadcast(eq("/consensus/transactions"), any());
        assertThat(broadcasts).as("同一笔不应重复上线").hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).as("同一笔不应重复上线").hasSize(1);
    }

    @Test
    @DisplayName("空列表不触发任何广播")
    void emptyListBroadcastsNothing() {
        configureWindow(20, 1000);

        client.broadcastTransactions(Collections.emptyList());
        client.broadcastTransactions(null);

        assertThat(broadcasts).isEmpty();
    }

    @Test
    @DisplayName("一次调用传入多笔时同样合并为单个 Packet")
    void multipleTransactionsInOneCallStayInOnePacket() {
        configureWindow(0, 1000);

        client.broadcastTransactions(Arrays.asList(tx(1, 100), tx(2, 200), tx(3, 300)));

        assertThat(broadcasts).hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(3);
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * REST 形态（{@code p2p.mode=rest}）的 P2P 广播合批。
 *
 * <p>补齐 2026-10-27 合批改动遗留的口子：此前只有 gRPC 形态
 * （{@code TransactionHandler}）做窗口合并，rest 形态仍然是「每笔 × 每个 peer」
 * 一次 HTTP POST。接收端 {@code /consensus/transactions} 本来就按列表遍历校验，
 * 一个 {@code Packet} 带多笔是协议支持的。</p>
 */
class RPCClientBatchingTest {

    private RPCClient client;
    private JSONEncodeDecoder codec;
    private final List<byte[]> broadcasts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        codec = new JSONEncodeDecoder();
        client = spy(new RPCClient());
        ReflectionTestUtils.setField(client, "codec", codec);
        doAnswer(inv -> {
            broadcasts.add(inv.getArgument(1));
            return null;
        }).when(client).broadcast(anyString(), any());
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
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
    void coalescesTransactionsWithinWindow() {
        configureWindow(80, 1000);

        client.broadcastTransactions(Collections.singletonList(tx(1, 100)));
        client.broadcastTransactions(Collections.singletonList(tx(2, 200)));
        client.broadcastTransactions(Collections.singletonList(tx(3, 300)));

        verify(client, timeout(3000).times(1)).broadcast(eq("/consensus/transactions"), any());
        assertThat(broadcasts).hasSize(1);
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(3);
    }

    @Test
    @DisplayName("单笔事务等窗口到期后才广播（延迟上限即窗口）")
    void singleTransactionWaitsForWindow() {
        // 窗口取大一点（2s），避免并行测试下"入队→断言"之间就到期
        configureWindow(2000, 1000);

        client.broadcastTransactions(Collections.singletonList(tx(1, 100)));

        assertThat(broadcasts).as("窗口未到期前不应广播").isEmpty();
        verify(client, timeout(8000).times(1)).broadcast(eq("/consensus/transactions"), any());
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
    void maxBatchSizeFlushesImmediately() {
        configureWindow(5000, 2);

        client.broadcastTransactions(Collections.singletonList(tx(1, 100)));
        assertThat(broadcasts).as("未达上限不应提前广播").isEmpty();

        client.broadcastTransactions(Collections.singletonList(tx(2, 200)));

        verify(client, timeout(3000).times(1)).broadcast(eq("/consensus/transactions"), any());
        assertThat(transactionsOf(broadcasts.get(0))).hasSize(2);
    }

    @Test
    @DisplayName("重复交易在合批后按交易哈希折叠，只广播一次")
    void duplicateTransactionsAreDeduplicated() {
        configureWindow(80, 1000);

        Transaction duplicate = tx(1, 100);
        client.broadcastTransactions(Collections.singletonList(duplicate));
        client.broadcastTransactions(Collections.singletonList(duplicate));

        verify(client, timeout(3000).times(1)).broadcast(eq("/consensus/transactions"), any());
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

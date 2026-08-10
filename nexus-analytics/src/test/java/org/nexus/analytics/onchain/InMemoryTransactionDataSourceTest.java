package org.nexus.analytics.onchain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryTransactionDataSource} 单元测试。
 *
 * <p>覆盖 fetchAll/fetchBetween/fetchByAddress/clear/feed 的全部分支与边界。
 */
class InMemoryTransactionDataSourceTest {

    private InMemoryTransactionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryTransactionDataSource();
    }

    private OnChainTransaction tx(String hash, String from, String to, Instant ts) {
        return OnChainTransaction.builder()
                .txHash(hash).fromAddress(from).toAddress(to)
                .amount(BigInteger.ONE).timestamp(ts)
                .status(OnChainTransaction.Status.SUCCESS).build();
    }

    @Test
    void fetchAll_emptyInitially_shouldReturnEmptyList() {
        assertTrue(dataSource.fetchAll().isEmpty());
    }

    @Test
    void feed_shouldAddTransactions() {
        dataSource.feed(List.of(tx("h1", "A", "B", Instant.now())));

        assertEquals(1, dataSource.fetchAll().size());
    }

    @Test
    void feed_null_shouldBeNoOp() {
        dataSource.feed(null);

        assertTrue(dataSource.fetchAll().isEmpty());
    }

    @Test
    void clear_shouldRemoveAllTransactions() {
        dataSource.feed(List.of(tx("h1", "A", "B", Instant.now())));
        dataSource.clear();

        assertTrue(dataSource.fetchAll().isEmpty());
    }

    @Test
    void fetchBetween_nullArgs_shouldReturnEmpty() {
        dataSource.feed(List.of(tx("h1", "A", "B", Instant.now())));

        assertTrue(dataSource.fetchBetween(null, Instant.now()).isEmpty());
        assertTrue(dataSource.fetchBetween(Instant.now(), null).isEmpty());
    }

    @Test
    void fetchBetween_inclusiveStartExclusiveEnd() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T01:00:00Z");
        Instant t3 = Instant.parse("2026-01-01T02:00:00Z");
        dataSource.feed(List.of(
                tx("h1", "A", "B", t1),
                tx("h2", "A", "B", t2),
                tx("h3", "A", "B", t3)));

        // [t1, t3)：含 t1、t2，不含 t3
        List<OnChainTransaction> result = dataSource.fetchBetween(t1, t3);
        assertEquals(2, result.size());
        assertEquals("h1", result.get(0).getTxHash());
        assertEquals("h2", result.get(1).getTxHash());
    }

    @Test
    void fetchBetween_shouldFilterNullTimestamp() {
        dataSource.feed(List.of(
                OnChainTransaction.builder().txHash("h1").fromAddress("A").toAddress("B")
                        .amount(BigInteger.ONE).timestamp(null)
                        .status(OnChainTransaction.Status.SUCCESS).build(),
                tx("h2", "A", "B", Instant.now())));

        List<OnChainTransaction> result = dataSource.fetchBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertEquals(1, result.size());
        assertEquals("h2", result.get(0).getTxHash());
    }

    @Test
    void fetchByAddress_nullOrBlank_shouldReturnEmpty() {
        dataSource.feed(List.of(tx("h1", "A", "B", Instant.now())));

        assertTrue(dataSource.fetchByAddress(null).isEmpty());
        assertTrue(dataSource.fetchByAddress("").isEmpty());
        assertTrue(dataSource.fetchByAddress("   ").isEmpty());
    }

    @Test
    void fetchByAddress_shouldMatchFromOrTo() {
        dataSource.feed(List.of(
                tx("h1", "A", "B", Instant.now()),
                tx("h2", "C", "A", Instant.now()),
                tx("h3", "X", "Y", Instant.now())));

        List<OnChainTransaction> result = dataSource.fetchByAddress("A");
        assertEquals(2, result.size());
    }

    @Test
    void fetchAll_shouldReturnDefensiveCopy() {
        dataSource.feed(List.of(tx("h1", "A", "B", Instant.now())));
        List<OnChainTransaction> snapshot = dataSource.fetchAll();
        // 修改快照不应影响数据源
        try {
            snapshot.add(tx("h2", "C", "D", Instant.now()));
        } catch (UnsupportedOperationException ignore) {
            // List.copyOf 返回不可变 list，符合预期
        }
        assertEquals(1, dataSource.fetchAll().size());
    }
}
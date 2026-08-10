package org.nexus.settlement.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryChainRecordSource} 与 {@link InMemoryBankRecordSource} 单元测试。
 */
class InMemoryRecordSourceTest {

    private InMemoryChainRecordSource chainSource;
    private InMemoryBankRecordSource bankSource;

    @BeforeEach
    void setUp() {
        chainSource = new InMemoryChainRecordSource();
        bankSource = new InMemoryBankRecordSource();
    }

    @Test
    void chainSource_emptyInitially_shouldReturnEmptyList() {
        assertTrue(chainSource.fetchChainRecords().isEmpty());
    }

    @Test
    void chainSource_feed_shouldReturnCopy() {
        chainSource.feed(List.of(
                new SettlementRecord("R1", new BigDecimal("100"), "USDT", Instant.now()),
                new SettlementRecord("R2", new BigDecimal("50"), "USDT", Instant.now())));

        List<SettlementRecord> first = chainSource.fetchChainRecords();
        assertEquals(2, first.size());

        // 返回的列表不可变（List.copyOf），修改应抛异常且不影响内部状态
        try {
            first.clear();
            org.junit.jupiter.api.Assertions.fail("Expected unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        assertEquals(2, chainSource.fetchChainRecords().size());
    }

    @Test
    void chainSource_feedNull_shouldBeNoOp() {
        chainSource.feed(null);
        assertTrue(chainSource.fetchChainRecords().isEmpty());
    }

    @Test
    void chainSource_clear_shouldEmptyRecords() {
        chainSource.feed(List.of(new SettlementRecord("R1", BigDecimal.TEN, "USDT", Instant.now())));
        chainSource.clear();
        assertTrue(chainSource.fetchChainRecords().isEmpty());
    }

    @Test
    void bankSource_emptyInitially_shouldReturnEmptyList() {
        assertTrue(bankSource.fetchBankRecords().isEmpty());
    }

    @Test
    void bankSource_feed_shouldReturnCopy() {
        bankSource.feed(List.of(
                new SettlementRecord("B1", new BigDecimal("100"), "USDT", Instant.now())));

        assertEquals(1, bankSource.fetchBankRecords().size());
    }

    @Test
    void bankSource_feedNull_shouldBeNoOp() {
        bankSource.feed(null);
        assertTrue(bankSource.fetchBankRecords().isEmpty());
    }

    @Test
    void bankSource_clear_shouldEmptyRecords() {
        bankSource.feed(List.of(new SettlementRecord("B1", BigDecimal.TEN, "USDT", Instant.now())));
        bankSource.clear();
        assertTrue(bankSource.fetchBankRecords().isEmpty());
    }
}
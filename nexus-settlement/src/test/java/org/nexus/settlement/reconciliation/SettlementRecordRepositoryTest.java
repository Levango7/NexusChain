package org.nexus.settlement.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SettlementRecordRepository} 集成测试（真实 H2 落库）。
 *
 * <p>覆盖：按 source 查询（CHAIN/BANK 隔离）、(reference, source) 幂等查询、
 * (reference, source) 唯一约束、按 source 清空。</p>
 */
@DataJpaTest
class SettlementRecordRepositoryTest {

    @Autowired
    private SettlementRecordRepository repository;

    private SettlementRecord record(String reference, String source, String amount) {
        return new SettlementRecord(reference, source,
                new BigDecimal(amount), "USD", Instant.now());
    }

    @Test
    void findBySource_shouldIsolateChainAndBank() {
        repository.save(record("O1", SettlementRecord.SOURCE_CHAIN, "100"));
        repository.save(record("O2", SettlementRecord.SOURCE_CHAIN, "50"));
        repository.save(record("O1", SettlementRecord.SOURCE_BANK, "100"));

        List<SettlementRecord> chain = repository.findBySource(SettlementRecord.SOURCE_CHAIN);
        List<SettlementRecord> bank = repository.findBySource(SettlementRecord.SOURCE_BANK);

        assertEquals(2, chain.size());
        assertEquals(1, bank.size());
        assertTrue(chain.stream().allMatch(r -> SettlementRecord.SOURCE_CHAIN.equals(r.getSource())));
    }

    @Test
    void findByReferenceAndSource_shouldReturnMatched() {
        repository.save(record("O1", SettlementRecord.SOURCE_CHAIN, "100"));

        Optional<SettlementRecord> hit = repository.findByReferenceAndSource(
                "O1", SettlementRecord.SOURCE_CHAIN);
        Optional<SettlementRecord> miss = repository.findByReferenceAndSource(
                "O2", SettlementRecord.SOURCE_CHAIN);

        assertTrue(hit.isPresent());
        assertEquals(0, new BigDecimal("100").compareTo(hit.get().getAmount()));
        assertTrue(miss.isEmpty());
    }

    @Test
    void duplicateReferenceAndSource_shouldBeRejected() {
        repository.save(record("O1", SettlementRecord.SOURCE_CHAIN, "100"));
        repository.flush();

        // 同 (reference, source) 的重复记录应被唯一约束拒绝（幂等防重）
        org.springframework.dao.DataIntegrityViolationException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () -> {
                            repository.save(record("O1", SettlementRecord.SOURCE_CHAIN, "200"));
                            repository.flush();
                        });
        org.junit.jupiter.api.Assertions.assertNotNull(ex);
    }

    @Test
    void deleteBySource_shouldClearOnlyThatSource() {
        repository.save(record("O1", SettlementRecord.SOURCE_CHAIN, "100"));
        repository.save(record("O1", SettlementRecord.SOURCE_BANK, "100"));

        repository.deleteBySource(SettlementRecord.SOURCE_CHAIN);

        assertEquals(0, repository.findBySource(SettlementRecord.SOURCE_CHAIN).size());
        assertEquals(1, repository.findBySource(SettlementRecord.SOURCE_BANK).size());
    }
}
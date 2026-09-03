package org.nexus.settlement.clearing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Ledger} 持久化（DB 模式）集成测试。
 *
 * <p>通过 {@code @JdbcTest} + H2 + {@code schema-ledger.sql} 真实落库，
 * 验证复式记账双写原子性、余额 SQL 聚合、分录顺序与 {@code (reference, account)} 幂等防重。</p>
 *
 * <p>与既有纯单测（内存 fallback，new Ledger()）互补，覆盖「真实落库」路径。</p>
 */
@JdbcTest
@Sql("/schema-ledger.sql")
class LedgerJdbcTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void bookSettlement_dbMode_shouldCreateTwoRowsAndAggregateBalance() {
        Ledger ledger = new Ledger(jdbcTemplate, transactionManager);

        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        ledger.bookSettlement("M001", new BigDecimal("50"), "O2");

        // 借账 2 条 + 贷账 2 条
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry", Integer.class);
        assertEquals(4, rows);

        // 贷方（MERCHANT:M001）余额 = 100 + 50 = 150
        assertEquals(0, new BigDecimal("150").compareTo(ledger.balanceOf("MERCHANT:M001")));
        // 借方（SETTLEMENT_PAYABLE）余额 = -(100 + 50) = -150（负债减少）
        assertEquals(0, new BigDecimal("-150").compareTo(ledger.balanceOf(Ledger.SETTLEMENT_PAYABLE)));
    }

    @Test
    void entriesOf_dbMode_shouldReturnInBookedOrder() {
        Ledger ledger = new Ledger(jdbcTemplate, transactionManager);
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        ledger.bookSettlement("M001", new BigDecimal("50"), "O2");

        List<LedgerEntry> entries = ledger.entriesOf("MERCHANT:M001");

        assertEquals(2, entries.size());
        assertEquals(LedgerEntry.Direction.CREDIT, entries.get(0).getDirection());
        assertEquals(0, new BigDecimal("100").compareTo(entries.get(0).getAmount()));
        assertEquals("O1", entries.get(0).getReference());
        assertEquals(0, new BigDecimal("50").compareTo(entries.get(1).getAmount()));
        assertEquals("O2", entries.get(1).getReference());
    }

    @Test
    void bookSettlement_duplicateReferenceAndAccount_shouldRollbackBothWrites() {
        Ledger ledger = new Ledger(jdbcTemplate, transactionManager);

        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");

        // 同一 (reference=O1, account=SETTLEMENT_PAYABLE) 的借方已存在 → 第二次撞唯一键整体回滚
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> ledger.bookSettlement("M001", new BigDecimal("200"), "O1"));

        // 回滚后不产生半账：仍是 2 条，平衡不动
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry", Integer.class);
        assertEquals(2, rows);
        assertEquals(0, new BigDecimal("100").compareTo(ledger.balanceOf("MERCHANT:M001")));
    }

    @Test
    void bookTransfer_dbMode_shouldMoveBalanceBetweenAccounts() {
        Ledger ledger = new Ledger(jdbcTemplate, transactionManager);

        ledger.bookTransfer("A", "B", new BigDecimal("30"), "T1");

        assertEquals(0, new BigDecimal("30").compareTo(ledger.balanceOf("B")));
        assertEquals(0, new BigDecimal("-30").compareTo(ledger.balanceOf("A")));
    }

    @Test
    void balanceOf_noRecords_shouldReturnZero() {
        Ledger ledger = new Ledger(jdbcTemplate, transactionManager);

        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.balanceOf("NON_EXISTENT")));
    }
}
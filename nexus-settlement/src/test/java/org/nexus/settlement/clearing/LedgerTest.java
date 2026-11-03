package org.nexus.settlement.clearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Ledger} 单元测试。
 */
class LedgerTest {

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
    }

    @Test
    void bookSettlement_shouldCreateBalancedEntries() {
        ledger.bookSettlement("M001", new BigDecimal("100.00"), "ORDER-1");

        assertEquals(new BigDecimal("-100.00"), ledger.balanceOf(Ledger.SETTLEMENT_PAYABLE));
        assertEquals(new BigDecimal("100.00"), ledger.balanceOf("MERCHANT:M001"));

        List<LedgerEntry> merchantEntries = ledger.entriesOf("MERCHANT:M001");
        assertEquals(1, merchantEntries.size());
        assertEquals(LedgerEntry.Direction.CREDIT, merchantEntries.get(0).getDirection());
        assertEquals("ORDER-1", merchantEntries.get(0).getReference());
    }

    @Test
    void bookSettlement_shouldIgnoreInvalidInput() {
        ledger.bookSettlement(null, BigDecimal.TEN, "ORDER-X");
        ledger.bookSettlement("M001", null, "ORDER-X");
        ledger.bookSettlement("M001", new BigDecimal("-5"), "ORDER-X");

        assertTrue(ledger.entriesOf(Ledger.SETTLEMENT_PAYABLE).isEmpty());
    }

    @Test
    void bookTransfer_shouldMoveBalance() {
        // 先让源账户有余额：模拟商户结算进源账户
        ledger.bookSettlement("ADDR_A", new BigDecimal("200"), "ORDER-A");

        ledger.bookTransfer("MERCHANT:ADDR_A", "TARGET_ADDR", new BigDecimal("200"), "SWEEP-1");

        assertEquals(BigDecimal.ZERO, ledger.balanceOf("MERCHANT:ADDR_A"));
        assertEquals(new BigDecimal("200"), ledger.balanceOf("TARGET_ADDR"));
    }

    @Test
    void balanceOf_unknownAccount_returnsZero() {
        assertEquals(BigDecimal.ZERO, ledger.balanceOf("NO_SUCH_ACCOUNT"));
    }

    @Test
    void multipleSettlements_shouldAccumulate() {
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        ledger.bookSettlement("M001", new BigDecimal("50"), "O2");
        ledger.bookSettlement("M002", new BigDecimal("30"), "O3");

        assertEquals(new BigDecimal("150"), ledger.balanceOf("MERCHANT:M001"));
        assertEquals(new BigDecimal("30"), ledger.balanceOf("MERCHANT:M002"));
        assertEquals(2, ledger.entriesOf("MERCHANT:M001").size());
    }

    @Test
    void reverseSettlement_shouldUndoBooking() {
        ledger.bookSettlement("M001", new BigDecimal("100.00"), "ORDER-1");
        ledger.reverseSettlement("M001", new BigDecimal("100.00"), "ORDER-1");

        // 借贷双方均回滚到 0（用 compareTo 比较，规避 BigDecimal scale 差异：0 与 0.00 不等）
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.balanceOf(Ledger.SETTLEMENT_PAYABLE)));
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.balanceOf("MERCHANT:M001")));

        // 冲正分录存在且 reference 带 :REV 后缀、方向与原单相反
        List<LedgerEntry> merchantEntries = ledger.entriesOf("MERCHANT:M001");
        assertEquals(2, merchantEntries.size());
        assertEquals(LedgerEntry.Direction.CREDIT, merchantEntries.get(0).getDirection());
        assertEquals(LedgerEntry.Direction.DEBIT, merchantEntries.get(1).getDirection());
        assertEquals("ORDER-1:REV", merchantEntries.get(1).getReference());
    }

    @Test
    void reverseSettlement_shouldIgnoreInvalidInput() {
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        ledger.reverseSettlement(null, BigDecimal.TEN, "O1");
        ledger.reverseSettlement("M001", null, "O1");
        ledger.reverseSettlement("M001", new BigDecimal("-5"), "O1");

        // 无效冲正不新增分录
        assertEquals(1, ledger.entriesOf("MERCHANT:M001").size());
        assertEquals(new BigDecimal("100"), ledger.balanceOf("MERCHANT:M001"));
    }
}

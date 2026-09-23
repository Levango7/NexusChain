package org.nexus.gateway.event;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.FinalityStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PaymentConfirmedEvent 测试 — 验证 finalityStatus 字段和构造器兼容性。
 */
class PaymentConfirmedEventTest {

    @Test
    void eightArgConstructorSetsFinalityStatus() {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100", FinalityStatus.FINALIZED);

        assertEquals(FinalityStatus.FINALIZED, event.getFinalityStatus());
    }

    @Test
    void eightArgConstructorSetsAllFields() {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100", FinalityStatus.OPTIMISTIC);

        assertEquals(1L, event.getOrderId());
        assertEquals("ORDER-001", event.getOrderNo());
        assertEquals(100L, event.getMerchantId());
        assertEquals("0xabc", event.getChainTxHash());
        assertEquals("0xpayer", event.getPayerAddress());
        assertEquals("100", event.getAmount());
        assertEquals(FinalityStatus.OPTIMISTIC, event.getFinalityStatus());
    }

    @Test
    void sevenArgConstructorDefaultsFinalityStatusToUnknown() {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100");

        assertEquals(FinalityStatus.UNKNOWN, event.getFinalityStatus());
    }

    @Test
    void sevenArgConstructorSetsOtherFields() {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100");

        assertEquals(1L, event.getOrderId());
        assertEquals("ORDER-001", event.getOrderNo());
        assertEquals(100L, event.getMerchantId());
        assertEquals("0xabc", event.getChainTxHash());
        assertEquals("0xpayer", event.getPayerAddress());
        assertEquals("100", event.getAmount());
    }

    @Test
    void getEventTypeReturnsPaymentConfirmed() {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100", FinalityStatus.FINALIZED);

        assertEquals("PAYMENT_CONFIRMED", event.getEventType());
    }

    @Test
    void getEventTypeReturnsPaymentConfirmedForSevenArg() {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100");

        assertEquals("PAYMENT_CONFIRMED", event.getEventType());
    }

    @Test
    void eightArgConstructorWithNullFinalityStatus() {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100", null);

        assertNull(event.getFinalityStatus());
    }

    @Test
    void eightArgConstructorWithUnknownFinalityStatus() {
        PaymentConfirmedEvent event = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100", FinalityStatus.UNKNOWN);

        assertEquals(FinalityStatus.UNKNOWN, event.getFinalityStatus());
    }

    @Test
    void sevenArgAndEightArgWithUnknownAreEquivalent() {
        PaymentConfirmedEvent sevenArg = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100");
        PaymentConfirmedEvent eightArgUnknown = new PaymentConfirmedEvent(
                this, 1L, "ORDER-001", 100L,
                "0xabc", "0xpayer", "100", FinalityStatus.UNKNOWN);

        assertEquals(sevenArg.getFinalityStatus(), eightArgUnknown.getFinalityStatus());
        assertEquals(sevenArg.getOrderId(), eightArgUnknown.getOrderId());
        assertEquals(sevenArg.getOrderNo(), eightArgUnknown.getOrderNo());
        assertEquals(sevenArg.getChainTxHash(), eightArgUnknown.getChainTxHash());
    }
}
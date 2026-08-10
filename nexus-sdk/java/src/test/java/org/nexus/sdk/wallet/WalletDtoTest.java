package org.nexus.sdk.wallet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wallet DTO 测试：WalletTransactionRequest / WalletTransactionResult /
 * WithdrawalRequest / WalletTier。
 */
class WalletDtoTest {

    @Test
    void walletTransactionRequest_allGettersAndSetters() {
        WalletTransactionRequest req = new WalletTransactionRequest();
        req.setFromAddress("0xfrom");
        req.setToAddress("0xto");
        req.setAmount(BigDecimal.TEN);
        req.setAsset("NEX");
        req.setMemo("test memo");
        req.setType(WalletTransactionRequest.Type.SETTLEMENT);
        req.setRequestId("req-1");

        assertEquals("0xfrom", req.getFromAddress());
        assertEquals("0xto", req.getToAddress());
        assertEquals(BigDecimal.TEN, req.getAmount());
        assertEquals("NEX", req.getAsset());
        assertEquals("test memo", req.getMemo());
        assertEquals(WalletTransactionRequest.Type.SETTLEMENT, req.getType());
        assertEquals("req-1", req.getRequestId());
    }

    @Test
    void walletTransactionRequest_constructorWithNullAsset_shouldDefaultToNEX() {
        WalletTransactionRequest req = new WalletTransactionRequest(
                WalletTransactionRequest.Type.REFUND, "from", "to", BigDecimal.ONE, null, null, "id");

        assertEquals("NEX", req.getAsset());
    }

    @Test
    void walletTransactionRequest_constructorWithCustomAsset_shouldKeepIt() {
        WalletTransactionRequest req = new WalletTransactionRequest(
                WalletTransactionRequest.Type.WITHDRAWAL, "from", "to", BigDecimal.ONE, "USDT", null, "id");

        assertEquals("USDT", req.getAsset());
    }

    @Test
    void walletTransactionRequest_equalsAndHashCode_shouldFollowContract() {
        WalletTransactionRequest r1 = new WalletTransactionRequest(
                WalletTransactionRequest.Type.SETTLEMENT, "from", "to", BigDecimal.ONE, "NEX", "m", "id1");
        WalletTransactionRequest r2 = new WalletTransactionRequest(
                WalletTransactionRequest.Type.SETTLEMENT, "from", "to", BigDecimal.ONE, "NEX", "m", "id1");
        WalletTransactionRequest r3 = new WalletTransactionRequest(
                WalletTransactionRequest.Type.REFUND, "from", "to", BigDecimal.ONE, "NEX", "m", "id1");

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        assertEquals(r1, r1);
        assertNotEquals(r1, null);
        assertNotEquals(r1, "string");
    }

    @Test
    void walletTransactionRequest_typeEnum_shouldHaveAllValues() {
        WalletTransactionRequest.Type[] types = WalletTransactionRequest.Type.values();
        assertEquals(4, types.length);
        assertEquals(WalletTransactionRequest.Type.SETTLEMENT, WalletTransactionRequest.Type.valueOf("SETTLEMENT"));
        assertEquals(WalletTransactionRequest.Type.REFUND, WalletTransactionRequest.Type.valueOf("REFUND"));
        assertEquals(WalletTransactionRequest.Type.WITHDRAWAL, WalletTransactionRequest.Type.valueOf("WITHDRAWAL"));
        assertEquals(WalletTransactionRequest.Type.SWEEP, WalletTransactionRequest.Type.valueOf("SWEEP"));
    }

    @Test
    void walletTransactionResult_allGettersAndSetters() {
        WalletTransactionResult res = new WalletTransactionResult();
        res.setTxHash("0xhash");
        res.setStatus(WalletTransactionResult.Status.SUCCESS);
        res.setConfirmations(12);
        res.setError(null);
        res.setSimulated(true);

        assertEquals("0xhash", res.getTxHash());
        assertEquals(WalletTransactionResult.Status.SUCCESS, res.getStatus());
        assertEquals(12, res.getConfirmations());
        assertEquals(null, res.getError());
        assertTrue(res.isSimulated());
        assertTrue(res.isSuccess());
    }

    @Test
    void walletTransactionResult_isSuccess_failedStatus_shouldReturnFalse() {
        WalletTransactionResult res = new WalletTransactionResult();
        res.setStatus(WalletTransactionResult.Status.FAILED);

        assertFalse(res.isSuccess());
    }

    @Test
    void walletTransactionResult_isSuccess_pendingStatus_shouldReturnFalse() {
        WalletTransactionResult res = new WalletTransactionResult();
        res.setStatus(WalletTransactionResult.Status.PENDING_CONFIRMATION);

        assertFalse(res.isSuccess());
    }

    @Test
    void walletTransactionResult_constructor_shouldSetAllFields() {
        WalletTransactionResult res = new WalletTransactionResult(
                "0xhash", WalletTransactionResult.Status.SUCCESS, 5, null, false);

        assertEquals("0xhash", res.getTxHash());
        assertEquals(WalletTransactionResult.Status.SUCCESS, res.getStatus());
        assertEquals(5, res.getConfirmations());
        assertEquals(null, res.getError());
        assertFalse(res.isSimulated());
    }

    @Test
    void walletTransactionResult_equalsAndHashCode_shouldFollowContract() {
        WalletTransactionResult r1 = new WalletTransactionResult(
                "0xh", WalletTransactionResult.Status.SUCCESS, 1, "err", true);
        WalletTransactionResult r2 = new WalletTransactionResult(
                "0xh", WalletTransactionResult.Status.SUCCESS, 1, "err", true);
        WalletTransactionResult r3 = new WalletTransactionResult(
                "0xh", WalletTransactionResult.Status.FAILED, 1, "err", true);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        assertEquals(r1, r1);
        assertNotEquals(r1, null);
        assertNotEquals(r1, "string");
    }

    @Test
    void walletTransactionResult_statusEnum_shouldHaveAllValues() {
        WalletTransactionResult.Status[] statuses = WalletTransactionResult.Status.values();
        assertEquals(3, statuses.length);
    }

    @Test
    void withdrawalRequest_allGettersAndSetters() {
        WithdrawalRequest req = new WithdrawalRequest();
        req.setRequestId("req-1");
        req.setToAddress("0xto");
        req.setAmount(BigDecimal.TEN);
        req.setCurrency("NEX");
        req.setStatus(WithdrawalRequest.WithdrawalStatus.APPROVED);
        req.setApprovers(Arrays.asList("approver1", "approver2"));
        req.setRequiredApprovers(3);
        req.setApprovedCount(2);
        req.setChainTxHash("0xtx");
        req.setRejectionReason(null);
        req.setCreatedAt(LocalDateTime.now());
        req.setExecutedAt(LocalDateTime.now());

        assertEquals("req-1", req.getRequestId());
        assertEquals("0xto", req.getToAddress());
        assertEquals(BigDecimal.TEN, req.getAmount());
        assertEquals("NEX", req.getCurrency());
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, req.getStatus());
        assertEquals(Arrays.asList("approver1", "approver2"), req.getApprovers());
        assertEquals(3, req.getRequiredApprovers());
        assertEquals(2, req.getApprovedCount());
        assertEquals("0xtx", req.getChainTxHash());
        assertEquals(null, req.getRejectionReason());
        assertNotNull(req.getCreatedAt());
        assertNotNull(req.getExecutedAt());
    }

    @Test
    void withdrawalRequest_defaultStatus_shouldBePending() {
        WithdrawalRequest req = new WithdrawalRequest();
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, req.getStatus());
    }

    @Test
    void withdrawalRequest_defaultApprovedCount_shouldBeZero() {
        WithdrawalRequest req = new WithdrawalRequest();
        assertEquals(0, req.getApprovedCount());
    }

    @Test
    void withdrawalRequest_defaultApprovers_shouldBeEmptyList() {
        WithdrawalRequest req = new WithdrawalRequest();
        assertNotNull(req.getApprovers());
        assertTrue(req.getApprovers().isEmpty());
    }

    @Test
    void withdrawalRequest_withdrawalStatusEnum_shouldHaveAllValues() {
        WithdrawalRequest.WithdrawalStatus[] statuses = WithdrawalRequest.WithdrawalStatus.values();
        assertEquals(5, statuses.length);
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, WithdrawalRequest.WithdrawalStatus.valueOf("PENDING"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, WithdrawalRequest.WithdrawalStatus.valueOf("APPROVED"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.REJECTED, WithdrawalRequest.WithdrawalStatus.valueOf("REJECTED"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, WithdrawalRequest.WithdrawalStatus.valueOf("EXECUTED"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, WithdrawalRequest.WithdrawalStatus.valueOf("FAILED"));
    }

    @Test
    void walletTier_shouldHaveThreeValues() {
        WalletTier[] tiers = WalletTier.values();
        assertEquals(3, tiers.length);
        assertEquals(WalletTier.HOT, WalletTier.valueOf("HOT"));
        assertEquals(WalletTier.WARM, WalletTier.valueOf("WARM"));
        assertEquals(WalletTier.COLD, WalletTier.valueOf("COLD"));
    }
}
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
        // 2026-10-25：3 → 5。新增 AWAITING_APPROVAL / EXECUTED_UNVERIFIED 是有意的契约变更：
        // gateway 执行腿会把大额签名的 2001 透传为新状态，旧枚举会反序列化失败。
        assertEquals(5, statuses.length);
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
        // 2026-10-12：6 个（原 5 个）—— 新增 EXECUTING 是**有意**的契约变更：
        // 执行方在签名/广播前用条件 UPDATE 抢占该状态，使并发/重复 execute 只有一个能通过
        // （修「同一笔提现被广播两次」）。本用例是契约门禁：改枚举必须同步改此处 + CHANGELOG。
        assertEquals(6, statuses.length);
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, WithdrawalRequest.WithdrawalStatus.valueOf("PENDING"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, WithdrawalRequest.WithdrawalStatus.valueOf("APPROVED"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTING, WithdrawalRequest.WithdrawalStatus.valueOf("EXECUTING"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.REJECTED, WithdrawalRequest.WithdrawalStatus.valueOf("REJECTED"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, WithdrawalRequest.WithdrawalStatus.valueOf("EXECUTED"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, WithdrawalRequest.WithdrawalStatus.valueOf("FAILED"));
    }

    @Test
    void walletTransactionResult_shouldParseApprovalStatusesFromGatewayJson() throws Exception {
        // 契约门禁（2026-10-25）：gateway 的 /api/v1/execution/execute 现在会返回
        // AWAITING_APPROVAL / EXECUTED_UNVERIFIED。旧枚举只有 3 个值，Jackson 反序列化
        // 会抛异常并被 wallet 客户端 catch 成"gateway unreachable"——把待审批误报成失败。
        assertEquals(5, WalletTransactionResult.Status.values().length);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        WalletTransactionResult awaiting = mapper.readValue(
                "{\"status\":\"AWAITING_APPROVAL\",\"approvalId\":\"ap-1\",\"confirmations\":0}",
                WalletTransactionResult.class);
        assertEquals(WalletTransactionResult.Status.AWAITING_APPROVAL, awaiting.getStatus());
        assertEquals("ap-1", awaiting.getApprovalId());
        assertFalse(awaiting.isSuccess());

        WalletTransactionResult unverified = mapper.readValue(
                "{\"status\":\"EXECUTED_UNVERIFIED\",\"error\":\"no txHash\"}",
                WalletTransactionResult.class);
        assertEquals(WalletTransactionResult.Status.EXECUTED_UNVERIFIED, unverified.getStatus());
        assertFalse(unverified.isSuccess());
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
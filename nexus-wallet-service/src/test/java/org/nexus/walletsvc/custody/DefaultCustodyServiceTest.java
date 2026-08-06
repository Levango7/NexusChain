package org.nexus.walletsvc.custody;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.sdk.wallet.WalletTier;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.approval.WithdrawalApprovalService;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultCustodyService} 单元测试（Phase 3 任务 T13）。
 *
 * <p>验证热/冷钱包余额管理、转账校验（含多签审批闸门）与策略再平衡。
 * {@link WithdrawalApprovalService} 通过 {@link MockitoExtension} Mock，
 * 覆盖正常流程与异常流程（审批服务缺失 / 审批拒绝 / 余额不足 / 冷钱包上限突破）。</p>
 *
 * <p>设计文档 §3.2：冷钱包出金需多签审批，{@code approvalId} 必须指向已 APPROVED 的
 * 审批记录；审批服务缺失时冷钱包出金整体禁用（fail closed）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultCustodyServiceTest {

    private static final String COLD_ADDR = "0xcoldwallet1234567890";

    @Mock private WithdrawalApprovalService approvalService;

    private CustodyPolicy policy;
    private DefaultCustodyService service;

    @BeforeEach
    void setUp() {
        policy = new CustodyPolicy(
                new BigDecimal("5000"),   // hotWalletCap
                new BigDecimal("20000"),  // warmWalletCap
                new BigDecimal("3000"));  // autoSweepThreshold
        policy.setColdWalletCap(new BigDecimal("20000"));
        policy.setHotWalletFloor(new BigDecimal("500"));
        service = new DefaultCustodyService(provider(policy), provider(approvalService));
    }

    /** Wrap a value in an ObjectProvider for constructor injection in tests. */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    /** Stub the approval service to accept the given approvalId (simulate APPROVED → EXECUTED). */
    private void stubApprovalOk(String approvalId) {
        WithdrawalRequest executed = new WithdrawalRequest();
        executed.setRequestId(approvalId);
        executed.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED);
        when(approvalService.executeApprovedWithdrawal(approvalId)).thenReturn(executed);
    }

    // ==================== seedBalances / getHotBalance / getColdBalance ====================

    @Test
    void seedBalances_setsInitialBalances() {
        service.seedBalances(new BigDecimal("1000"), new BigDecimal("9000"));

        assertEquals(0, new BigDecimal("1000").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("9000").compareTo(service.getColdBalance()));
    }

    @Test
    void getHotBalance_defaultZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getHotBalance()));
    }

    @Test
    void getColdBalance_defaultZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getColdBalance()));
    }

    // ==================== depositToCold ====================

    @Test
    void depositToCold_movesFundsAndReturnsTxHash() {
        service.seedBalances(new BigDecimal("1000"), new BigDecimal("9000"));

        String txHash = service.depositToCold(COLD_ADDR, new BigDecimal("400"));

        assertNotNull(txHash);
        assertTrue(txHash.startsWith("SIMULATED-"));
        assertEquals(0, new BigDecimal("600").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("9400").compareTo(service.getColdBalance()));
    }

    @Test
    void depositToCold_nullAddressThrows() {
        service.seedBalances(new BigDecimal("1000"), BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> service.depositToCold(null, new BigDecimal("100")));
    }

    @Test
    void depositToCold_emptyAddressThrows() {
        service.seedBalances(new BigDecimal("1000"), BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> service.depositToCold("", new BigDecimal("100")));
    }

    @Test
    void depositToCold_nullAmountThrows() {
        service.seedBalances(new BigDecimal("1000"), BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> service.depositToCold(COLD_ADDR, null));
    }

    @Test
    void depositToCold_zeroAmountThrows() {
        service.seedBalances(new BigDecimal("1000"), BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> service.depositToCold(COLD_ADDR, BigDecimal.ZERO));
    }

    @Test
    void depositToCold_negativeAmountThrows() {
        service.seedBalances(new BigDecimal("1000"), BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> service.depositToCold(COLD_ADDR, new BigDecimal("-1")));
    }

    @Test
    void depositToCold_insufficientHotThrows() {
        service.seedBalances(new BigDecimal("100"), BigDecimal.ZERO);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.depositToCold(COLD_ADDR, new BigDecimal("500")));
        assertTrue(ex.getMessage().contains("insufficient hot balance"));
    }

    @Test
    void depositToCold_coldCapBreachedThrows() {
        policy.setColdWalletCap(new BigDecimal("9500"));
        service.seedBalances(new BigDecimal("1000"), new BigDecimal("9000"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.depositToCold(COLD_ADDR, new BigDecimal("1000"))); // projected 10000 > 9500
        assertTrue(ex.getMessage().contains("cold wallet cap breached"));

        // 余额保持不变
        assertEquals(0, new BigDecimal("9000").compareTo(service.getColdBalance()));
        assertEquals(0, new BigDecimal("1000").compareTo(service.getHotBalance()));
    }

    @Test
    void depositToCold_noPolicySkipsCapCheck() {
        // 无 custodyPolicy 时冷钱包上限校验跳过
        DefaultCustodyService svc = new DefaultCustodyService(
                provider(null), provider(approvalService));
        svc.seedBalances(new BigDecimal("1000"), BigDecimal.ZERO);

        String txHash = svc.depositToCold(COLD_ADDR, new BigDecimal("1000"));
        assertNotNull(txHash);
        assertEquals(0, BigDecimal.ZERO.compareTo(svc.getHotBalance()));
        assertEquals(0, new BigDecimal("1000").compareTo(svc.getColdBalance()));
    }

    // ==================== withdrawFromCold ====================

    @Test
    void withdrawFromCold_movesFundsAfterApproval() {
        service.seedBalances(new BigDecimal("100"), new BigDecimal("9000"));
        String approvalId = "WD-approved-1";
        stubApprovalOk(approvalId);

        String txHash = service.withdrawFromCold(COLD_ADDR, new BigDecimal("300"), approvalId);

        assertNotNull(txHash);
        assertTrue(txHash.startsWith("SIMULATED-"));
        assertEquals(0, new BigDecimal("400").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("8700").compareTo(service.getColdBalance()));
        verify(approvalService).executeApprovedWithdrawal(approvalId);
    }

    @Test
    void withdrawFromCold_nullAddressThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        assertThrows(IllegalArgumentException.class,
                () -> service.withdrawFromCold(null, new BigDecimal("100"), "approval-1"));
    }

    @Test
    void withdrawFromCold_emptyAddressThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        assertThrows(IllegalArgumentException.class,
                () -> service.withdrawFromCold("", new BigDecimal("100"), "approval-1"));
    }

    @Test
    void withdrawFromCold_nullAmountThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        assertThrows(IllegalArgumentException.class,
                () -> service.withdrawFromCold(COLD_ADDR, null, "approval-1"));
    }

    @Test
    void withdrawFromCold_zeroAmountThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        assertThrows(IllegalArgumentException.class,
                () -> service.withdrawFromCold(COLD_ADDR, BigDecimal.ZERO, "approval-1"));
    }

    @Test
    void withdrawFromCold_nullApprovalIdThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        assertThrows(IllegalArgumentException.class,
                () -> service.withdrawFromCold(COLD_ADDR, new BigDecimal("100"), null));
    }

    @Test
    void withdrawFromCold_emptyApprovalIdThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        assertThrows(IllegalArgumentException.class,
                () -> service.withdrawFromCold(COLD_ADDR, new BigDecimal("100"), ""));
    }

    @Test
    void withdrawFromCold_noApprovalServiceThrows() {
        // fail closed：审批服务缺失时冷钱包出金整体禁用
        DefaultCustodyService svc = new DefaultCustodyService(
                provider(policy), provider(null));
        svc.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> svc.withdrawFromCold(COLD_ADDR, new BigDecimal("100"), "approval-1"));
        assertTrue(ex.getMessage().contains("cold withdrawals are disabled"));
    }

    @Test
    void withdrawFromCold_insufficientColdThrowsBeforeApproval() {
        // 余额不足时在调用审批服务之前抛出，审批不被消耗
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("100"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.withdrawFromCold(COLD_ADDR, new BigDecimal("500"), "approval-1"));
        assertTrue(ex.getMessage().contains("insufficient cold balance"));
        // 审批服务未被调用（余额校验在前）
        verify(approvalService, never()).executeApprovedWithdrawal(anyString());
    }

    @Test
    void withdrawFromCold_approvalNotFoundPropagates() {
        // 审批服务抛 IllegalArgumentException（approvalId 不存在）→ 透传，余额不变
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        when(approvalService.executeApprovedWithdrawal("WD-nonexistent"))
                .thenThrow(new IllegalArgumentException("withdrawal request not found: WD-nonexistent"));

        assertThrows(IllegalArgumentException.class,
                () -> service.withdrawFromCold(COLD_ADDR, new BigDecimal("100"), "WD-nonexistent"));
        assertEquals(0, new BigDecimal("1000").compareTo(service.getColdBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getHotBalance()));
    }

    @Test
    void withdrawFromCold_pendingApprovalPropagatesIllegalState() {
        // 审批服务抛 IllegalStateException（approvalId 非 APPROVED）→ 透传，余额不变
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        when(approvalService.executeApprovedWithdrawal("WD-pending"))
                .thenThrow(new IllegalStateException("request is not approved"));

        assertThrows(IllegalStateException.class,
                () -> service.withdrawFromCold(COLD_ADDR, new BigDecimal("100"), "WD-pending"));
        assertEquals(0, new BigDecimal("1000").compareTo(service.getColdBalance()));
    }

    // ==================== rebalance ====================

    @Test
    void rebalance_sweepsExcessToCold() {
        // 热钱包 4000 > autoSweepThreshold 3000，应扫 1000 到冷钱包
        service.seedBalances(new BigDecimal("4000"), new BigDecimal("1000"));

        service.rebalance(WalletTier.COLD);

        assertEquals(0, new BigDecimal("3000").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("2000").compareTo(service.getColdBalance()));
    }

    @Test
    void rebalance_pullsFromColdBelowFloor() {
        // 热钱包 100 < floor 500，应从冷钱包回补 400（内部受控路径，无需外部审批）
        service.seedBalances(new BigDecimal("100"), new BigDecimal("5000"));

        service.rebalance(WalletTier.HOT);

        assertEquals(0, new BigDecimal("500").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("4600").compareTo(service.getColdBalance()));
        // 内部回补不经过审批服务
        verify(approvalService, never()).executeApprovedWithdrawal(anyString());
    }

    @Test
    void rebalance_noPolicySkips() {
        DefaultCustodyService svc = new DefaultCustodyService(
                provider(null), provider(approvalService));
        svc.seedBalances(new BigDecimal("999999"), BigDecimal.ZERO);

        // 无策略 → 跳过，余额不变
        svc.rebalance(WalletTier.COLD);

        assertEquals(0, new BigDecimal("999999").compareTo(svc.getHotBalance()));
    }

    @Test
    void rebalance_sweepColdCapBreachedSkipsSafely() {
        // sweep 时冷钱包上限突破 → catch 异常，热钱包仍超阈值但余额不变（安全降级）
        policy.setColdWalletCap(new BigDecimal("1500"));
        service.seedBalances(new BigDecimal("4000"), new BigDecimal("1000"));
        // sweep 1000 到冷钱包 → projected 2000 > cap 1500 → depositToCold 抛异常被 catch

        service.rebalance(WalletTier.COLD);

        // sweep 失败，余额保持原值
        assertEquals(0, new BigDecimal("4000").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("1000").compareTo(service.getColdBalance()));
    }

    @Test
    void rebalance_floorPullColdInsufficientSkips() {
        // 热钱包低于 floor 但冷钱包余额不足以回补 → 跳过
        policy.setHotWalletFloor(new BigDecimal("5000"));
        service.seedBalances(new BigDecimal("100"), new BigDecimal("100")); // cold 100 < deficit 4900

        service.rebalance(WalletTier.HOT);

        assertEquals(0, new BigDecimal("100").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("100").compareTo(service.getColdBalance()));
    }

    @Test
    void rebalance_withinThresholdsNoOp() {
        // 热钱包在 floor 与 sweepThreshold 之间 → 无操作
        service.seedBalances(new BigDecimal("1500"), new BigDecimal("5000"));

        service.rebalance(WalletTier.COLD);

        assertEquals(0, new BigDecimal("1500").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("5000").compareTo(service.getColdBalance()));
    }

    // ==================== isColdCustody ====================

    @Test
    void isColdCustody_coldPrefixReturnsTrue() {
        assertTrue(service.isColdCustody("cold-wallet-1"));
        assertTrue(service.isColdCustody("COLD-STORAGE-XYZ"));
    }

    @Test
    void isColdCustody_nonColdReturnsFalse() {
        assertFalse(service.isColdCustody("hot-wallet-1"));
        assertFalse(service.isColdCustody("warm-vault-1"));
    }

    @Test
    void isColdCustody_nullReturnsFalse() {
        assertFalse(service.isColdCustody(null));
    }

    @Test
    void isColdCustody_emptyReturnsFalse() {
        assertFalse(service.isColdCustody(""));
    }

    // ==================== getCustodyTier ====================

    @Test
    void getCustodyTier_coldPrefixReturnsCold() {
        assertEquals(WalletTier.COLD.name(), service.getCustodyTier("cold-wallet-1"));
        assertEquals(WalletTier.COLD.name(), service.getCustodyTier("COLD-STORAGE"));
    }

    @Test
    void getCustodyTier_warmPrefixReturnsWarm() {
        assertEquals(WalletTier.WARM.name(), service.getCustodyTier("warm-vault-1"));
        assertEquals(WalletTier.WARM.name(), service.getCustodyTier("WARM-VAULT"));
    }

    @Test
    void getCustodyTier_otherReturnsHot() {
        assertEquals(WalletTier.HOT.name(), service.getCustodyTier("hot-wallet-1"));
        assertEquals(WalletTier.HOT.name(), service.getCustodyTier("default-wallet"));
    }

    @Test
    void getCustodyTier_nullReturnsHot() {
        assertEquals(WalletTier.HOT.name(), service.getCustodyTier(null));
    }

    @Test
    void getCustodyTier_emptyReturnsHot() {
        assertEquals(WalletTier.HOT.name(), service.getCustodyTier(""));
    }
}
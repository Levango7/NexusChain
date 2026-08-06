package org.nexus.wallet.custody;

import org.junit.Before;
import org.junit.Test;
import org.nexus.wallet.approval.DefaultApprovalPolicy;
import org.nexus.wallet.approval.DefaultWithdrawalApprovalService;
import org.nexus.wallet.approval.WithdrawalRequest;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.junit.Assert.*;

/**
 * {@link DefaultCustodyService} 单元测试：验证热/冷钱包余额管理、
 * 转账校验（含多签审批闸门）与策略再平衡。
 */
public class DefaultCustodyServiceTest {

    private CustodyPolicy policy;
    private DefaultWithdrawalApprovalService approvalService;
    private DefaultCustodyService service;

    @Before
    public void setUp() {
        policy = new CustodyPolicy(
                new BigDecimal("5000"),   // hotWalletCap
                new BigDecimal("20000"),  // warmWalletCap
                new BigDecimal("3000"));  // autoSweepThreshold
        policy.setColdWalletCap(new BigDecimal("20000")); // 冷钱包上限
        policy.setHotWalletFloor(new BigDecimal("500"));
        DefaultApprovalPolicy approvalPolicy = new DefaultApprovalPolicy();
        approvalPolicy.addToWhitelist("0xcold");
        approvalService = new DefaultWithdrawalApprovalService(approvalPolicy);
        service = new DefaultCustodyService(provider(policy), provider(approvalService));
    }

    /** Wrap a value in an ObjectProvider for constructor injection in tests. */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<T>() {
            public T getObject(Object... args) { return value; }
            public T getObject() { return value; }
            public T getIfAvailable() { return value; }
            public T getIfUnique() { return value; }
        };
    }

    /** Create + fully approve a withdrawal request, returning its request ID. */
    private String approveWithdrawal(String to, BigDecimal amount) {
        WithdrawalRequest request = approvalService.requestWithdrawal(to, amount, "NEX");
        approvalService.approve(request.getRequestId(), "approver-1");
        return request.getRequestId();
    }

    @Test
    public void testSeedBalances_setsInitial() {
        service.seedBalances(new BigDecimal("1000"), new BigDecimal("9000"));

        assertEquals(0, new BigDecimal("1000").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("9000").compareTo(service.getColdBalance()));
    }

    @Test
    public void testDepositToCold_movesFunds() {
        service.seedBalances(new BigDecimal("1000"), new BigDecimal("9000"));

        String txHash = service.depositToCold("0xcold", new BigDecimal("400"));

        assertNotNull(txHash);
        assertTrue(txHash.startsWith("SIMULATED-"));
        assertEquals(0, new BigDecimal("600").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("9400").compareTo(service.getColdBalance()));
    }

    @Test(expected = IllegalStateException.class)
    public void testDepositToCold_insufficientHotThrows() {
        service.seedBalances(new BigDecimal("100"), BigDecimal.ZERO);
        service.depositToCold("0xcold", new BigDecimal("500"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDepositToCold_nonPositiveAmountThrows() {
        service.seedBalances(new BigDecimal("1000"), BigDecimal.ZERO);
        service.depositToCold("0xcold", BigDecimal.ZERO);
    }

    @Test
    public void testDepositToCold_coldCapEnforced() {
        // warmWalletCap=20000 但 coldWalletCap=9500：突破的是冷钱包上限（回归旧缺陷 :69）
        policy.setColdWalletCap(new BigDecimal("9500"));
        service.seedBalances(new BigDecimal("1000"), new BigDecimal("9000"));

        try {
            service.depositToCold("0xcold", new BigDecimal("1000")); // projected 10000 > 9500
            fail("expected cold wallet cap breach rejection");
        } catch (IllegalStateException expected) {
        }
        assertEquals(0, new BigDecimal("9000").compareTo(service.getColdBalance()));
        assertEquals(0, new BigDecimal("1000").compareTo(service.getHotBalance()));
    }

    @Test
    public void testWithdrawFromCold_movesFunds() {
        service.seedBalances(new BigDecimal("100"), new BigDecimal("9000"));
        String approvalId = approveWithdrawal("0xcold", new BigDecimal("300"));

        String txHash = service.withdrawFromCold("0xcold", new BigDecimal("300"), approvalId);

        assertNotNull(txHash);
        assertTrue(txHash.startsWith("SIMULATED-"));
        assertEquals(0, new BigDecimal("400").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("8700").compareTo(service.getColdBalance()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWithdrawFromCold_missingApprovalThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        service.withdrawFromCold("0xcold", new BigDecimal("100"), null);
    }

    @Test
    public void testWithdrawFromCold_arbitraryApprovalIdRejected() {
        // 回归测试（旧缺陷 :91-93）：任意字符串不再能通过审批
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));

        try {
            service.withdrawFromCold("0xcold", new BigDecimal("100"), "APPROVAL-1");
            fail("expected rejection of arbitrary approvalId");
        } catch (IllegalArgumentException expected) {
        }
        assertEquals(0, new BigDecimal("1000").compareTo(service.getColdBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getHotBalance()));
    }

    @Test
    public void testWithdrawFromCold_pendingApprovalRejected() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        WithdrawalRequest pending = approvalService.requestWithdrawal("0xcold", new BigDecimal("100"), "NEX");

        try {
            service.withdrawFromCold("0xcold", new BigDecimal("100"), pending.getRequestId());
            fail("expected rejection of pending approval");
        } catch (IllegalStateException expected) {
        }
        assertEquals(0, new BigDecimal("1000").compareTo(service.getColdBalance()));
    }

    @Test
    public void testWithdrawFromCold_approvalConsumedPreventsReplay() {
        service.seedBalances(new BigDecimal("100"), new BigDecimal("1000"));
        String approvalId = approveWithdrawal("0xcold", new BigDecimal("100"));
        service.withdrawFromCold("0xcold", new BigDecimal("100"), approvalId);

        try {
            service.withdrawFromCold("0xcold", new BigDecimal("100"), approvalId);
            fail("expected rejection of replayed approvalId");
        } catch (IllegalStateException expected) {
        }
        // 重放被拒，余额保持不变
        assertEquals(0, new BigDecimal("200").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("900").compareTo(service.getColdBalance()));
    }

    @Test(expected = IllegalStateException.class)
    public void testWithdrawFromCold_insufficientColdThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("100"));
        String approvalId = approveWithdrawal("0xcold", new BigDecimal("500"));
        service.withdrawFromCold("0xcold", new BigDecimal("500"), approvalId);
    }

    @Test
    public void testWithdrawFromCold_insufficientColdDoesNotConsumeApproval() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("100"));
        String approvalId = approveWithdrawal("0xcold", new BigDecimal("500"));

        try {
            service.withdrawFromCold("0xcold", new BigDecimal("500"), approvalId);
            fail("expected insufficient balance rejection");
        } catch (IllegalStateException expected) {
        }
        // 余额不足时审批未被消耗，补充资金后可重试
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED,
                approvalService.getRequest(approvalId).getStatus());
    }

    @Test
    public void testRebalance_sweepsExcessToCold() {
        // 热钱包 4000 > autoSweepThreshold 3000，应扫 1000 到冷钱包
        service.seedBalances(new BigDecimal("4000"), new BigDecimal("1000"));

        service.rebalance(WalletTier.COLD);

        assertEquals(0, new BigDecimal("3000").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("2000").compareTo(service.getColdBalance()));
    }

    @Test
    public void testRebalance_pullsFromColdBelowFloor() {
        // 热钱包 100 < floor 500，应从冷钱包回补 400（内部受控路径，无需外部审批）
        service.seedBalances(new BigDecimal("100"), new BigDecimal("5000"));

        service.rebalance(WalletTier.HOT);

        assertEquals(0, new BigDecimal("500").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("4600").compareTo(service.getColdBalance()));
    }
}

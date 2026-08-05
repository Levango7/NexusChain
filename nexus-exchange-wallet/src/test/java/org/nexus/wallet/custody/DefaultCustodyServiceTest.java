package org.nexus.wallet.custody;

import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.junit.Assert.*;

/**
 * {@link DefaultCustodyService} 单元测试：验证热/冷钱包余额管理、
 * 转账校验与策略再平衡。
 */
public class DefaultCustodyServiceTest {

    private CustodyPolicy policy;
    private DefaultCustodyService service;

    @Before
    public void setUp() {
        policy = new CustodyPolicy(
                new BigDecimal("5000"),   // hotWalletCap
                new BigDecimal("20000"),  // warmWalletCap（冷钱包上限）
                new BigDecimal("3000"));  // autoSweepThreshold
        policy.setHotWalletFloor(new BigDecimal("500"));
        service = new DefaultCustodyService(provider(policy));
    }

    /** Wrap a policy in an ObjectProvider for constructor injection in tests. */
    private static ObjectProvider<CustodyPolicy> provider(CustodyPolicy policy) {
        return new ObjectProvider<CustodyPolicy>() {
            public CustodyPolicy getObject(Object... args) { return policy; }
            public CustodyPolicy getObject() { return policy; }
            public CustodyPolicy getIfAvailable() { return policy; }
            public CustodyPolicy getIfUnique() { return policy; }
        };
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
    public void testWithdrawFromCold_movesFunds() {
        service.seedBalances(new BigDecimal("100"), new BigDecimal("9000"));

        String txHash = service.withdrawFromCold("0xcold", new BigDecimal("300"), "APPROVAL-1");

        assertNotNull(txHash);
        assertEquals(0, new BigDecimal("400").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("8700").compareTo(service.getColdBalance()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWithdrawFromCold_missingApprovalThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("1000"));
        service.withdrawFromCold("0xcold", new BigDecimal("100"), null);
    }

    @Test(expected = IllegalStateException.class)
    public void testWithdrawFromCold_insufficientColdThrows() {
        service.seedBalances(BigDecimal.ZERO, new BigDecimal("100"));
        service.withdrawFromCold("0xcold", new BigDecimal("500"), "APPROVAL-1");
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
        // 热钱包 100 < floor 500，应从冷钱包回补 400
        service.seedBalances(new BigDecimal("100"), new BigDecimal("5000"));

        service.rebalance(WalletTier.HOT);

        assertEquals(0, new BigDecimal("500").compareTo(service.getHotBalance()));
        assertEquals(0, new BigDecimal("4600").compareTo(service.getColdBalance()));
    }
}

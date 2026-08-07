package org.nexus.walletsvc.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.wallet.WalletTier;
import org.nexus.walletsvc.custody.CustodyService;
import org.nexus.walletsvc.entity.CustodyBalanceEntity;
import org.nexus.walletsvc.repository.CustodyBalanceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CustodyService} 集成测试（Phase 4 任务 #74，设计文档 §4.6.2 / §4.4.1）。
 *
 * <p>使用 {@code @SpringBootTest} + H2 内存数据库，验证 {@code DefaultCustodyService}
 * 的完整流程：set balance → deposit → rebalance，确认数据库中余额正确更新
 * （替代原 AtomicReference 内存存储）。</p>
 *
 * <p>验证要点：
 * <ul>
 *   <li>数据库持久化：Service 操作后 Repository 查询应反映最新余额</li>
 *   <li>@Transactional 事务边界：写操作正确提交到数据库</li>
 *   <li>乐观锁 @Version：并发更新由 JPA 自动管理</li>
 *   <li>余额校验：insufficient hot balance 抛 IllegalStateException</li>
 * </ul>
 * </p>
 *
 * <p>注：{@code seedBalances()} 已标注 {@code @Deprecated}（Phase 4 后由 Flyway V2
 * 预置），本测试通过 {@link CustodyBalanceRepository} 直接设置余额。</p>
 *
 * <p>类级 {@link Transactional} 使每个测试方法在独立事务中执行并默认回滚，
 * 避免修改 HOT/COLD 余额后污染共享 H2 数据库状态（如影响 {@code RepositoryIntegrationTest}
 * 的 Flyway V2 seed 断言）。{@code @BeforeEach resetBalances} 在同一事务内执行，
 * 保证每个测试方法起始时 HOT/COLD 余额为 0。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustodyServiceIntegrationTest {

    @Autowired
    private CustodyService custodyService;

    @Autowired
    private CustodyBalanceRepository custodyBalanceRepository;

    /** 通过 Repository 直接设置 HOT / COLD 余额（替代已 @Deprecated 的 seedBalances）。 */
    private void setBalances(BigDecimal hot, BigDecimal cold) {
        CustodyBalanceEntity hotEntity = custodyBalanceRepository.findByTier("HOT").orElseThrow();
        hotEntity.setBalance(hot);
        custodyBalanceRepository.save(hotEntity);

        CustodyBalanceEntity coldEntity = custodyBalanceRepository.findByTier("COLD").orElseThrow();
        coldEntity.setBalance(cold);
        custodyBalanceRepository.save(coldEntity);
    }

    @BeforeEach
    void resetBalances() {
        // 每个测试前重置 HOT / COLD 余额为 0（Flyway V2 预置为 0，但前序测试可能已修改）
        setBalances(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("初始余额：HOT=0, COLD=0（Flyway V2 seed）")
    void initialBalancesAreZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(custodyService.getHotBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(custodyService.getColdBalance()));
    }

    @Test
    @DisplayName("setBalances: 通过 Repository 设置余额后 Service 读取正确")
    void setBalances_serviceReadsFromDatabase() {
        setBalances(new BigDecimal("1000"), new BigDecimal("9000"));

        // 通过 Service 查询
        assertEquals(0, new BigDecimal("1000").compareTo(custodyService.getHotBalance()));
        assertEquals(0, new BigDecimal("9000").compareTo(custodyService.getColdBalance()));

        // 通过 Repository 直接查询，验证数据库持久化
        CustodyBalanceEntity hot = custodyBalanceRepository.findByTier("HOT").orElseThrow();
        CustodyBalanceEntity cold = custodyBalanceRepository.findByTier("COLD").orElseThrow();
        assertEquals(0, new BigDecimal("1000").compareTo(hot.getBalance()));
        assertEquals(0, new BigDecimal("9000").compareTo(cold.getBalance()));
    }

    @Test
    @DisplayName("depositToCold: HOT→COLD 转账后数据库余额正确更新")
    void depositToCold_updatesDatabaseBalances() {
        setBalances(new BigDecimal("1000"), BigDecimal.ZERO);

        String txHash = custodyService.depositToCold("cold-wallet-addr", new BigDecimal("400"));

        assertNotNull(txHash);
        assertTrue(txHash.startsWith("SIMULATED-"));

        // 通过 Repository 验证数据库状态
        CustodyBalanceEntity hot = custodyBalanceRepository.findByTier("HOT").orElseThrow();
        CustodyBalanceEntity cold = custodyBalanceRepository.findByTier("COLD").orElseThrow();
        assertEquals(0, new BigDecimal("600").compareTo(hot.getBalance()),
                "HOT 应从 1000 减至 600");
        assertEquals(0, new BigDecimal("400").compareTo(cold.getBalance()),
                "COLD 应从 0 增至 400");
    }

    @Test
    @DisplayName("depositToCold: HOT 余额不足时抛异常且数据库不变")
    void depositToCold_insufficientHotBalanceRollback() {
        setBalances(new BigDecimal("100"), BigDecimal.ZERO);

        assertThrows(IllegalStateException.class,
                () -> custodyService.depositToCold("cold-addr", new BigDecimal("500")));

        // 数据库余额不变（@Transactional 回滚）
        CustodyBalanceEntity hot = custodyBalanceRepository.findByTier("HOT").orElseThrow();
        CustodyBalanceEntity cold = custodyBalanceRepository.findByTier("COLD").orElseThrow();
        assertEquals(0, new BigDecimal("100").compareTo(hot.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(cold.getBalance()));
    }

    @Test
    @DisplayName("depositToCold: 无效参数校验")
    void depositToCold_invalidParameters() {
        setBalances(new BigDecimal("1000"), BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class,
                () -> custodyService.depositToCold(null, new BigDecimal("100")));
        assertThrows(IllegalArgumentException.class,
                () -> custodyService.depositToCold("", new BigDecimal("100")));
        assertThrows(IllegalArgumentException.class,
                () -> custodyService.depositToCold("cold-addr", null));
        assertThrows(IllegalArgumentException.class,
                () -> custodyService.depositToCold("cold-addr", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> custodyService.depositToCold("cold-addr", new BigDecimal("-1")));
    }

    @Test
    @DisplayName("rebalance: 无 CustodyPolicy 时安全跳过")
    void rebalance_noPolicySkipsSafely() {
        setBalances(new BigDecimal("5000"), BigDecimal.ZERO);

        // rebalance 需要 CustodyPolicy，但默认无 policy → 跳过
        // 此测试验证 rebalance 不抛异常（无 policy 时安全降级）
        custodyService.rebalance(WalletTier.COLD);

        // 无 policy 时余额不变
        assertEquals(0, new BigDecimal("5000").compareTo(custodyService.getHotBalance()));
    }

    @Test
    @DisplayName("getHotBalance/getColdBalance: 直接读数据库")
    void getBalances_readsFromDatabase() {
        // 直接通过 Repository 修改数据库
        setBalances(new BigDecimal("1234.567"), new BigDecimal("9876.543"));

        // Service 读取应反映数据库最新值
        assertEquals(0, new BigDecimal("1234.567").compareTo(custodyService.getHotBalance()));
        assertEquals(0, new BigDecimal("9876.543").compareTo(custodyService.getColdBalance()));
    }

    @Test
    @DisplayName("isColdCustody/getCustodyTier: 钱包层级判断")
    void custodyTierClassification() {
        assertTrue(custodyService.isColdCustody("cold-wallet-001"));
        assertTrue(!custodyService.isColdCustody("hot-wallet-001"));
        assertTrue(!custodyService.isColdCustody(null));
        assertTrue(!custodyService.isColdCustody(""));

        assertEquals("COLD", custodyService.getCustodyTier("cold-wallet-001"));
        assertEquals("HOT", custodyService.getCustodyTier("hot-wallet-001"));
        assertEquals("WARM", custodyService.getCustodyTier("warm-wallet-001"));
        assertEquals("HOT", custodyService.getCustodyTier(""));
    }

    @Test
    @DisplayName("depositToCold: 连续多次转账累积正确")
    void depositToCold_multipleAccumulatesCorrectly() {
        setBalances(new BigDecimal("1000"), BigDecimal.ZERO);

        custodyService.depositToCold("cold-addr", new BigDecimal("100"));
        custodyService.depositToCold("cold-addr", new BigDecimal("200"));
        custodyService.depositToCold("cold-addr", new BigDecimal("300"));

        assertEquals(0, new BigDecimal("400").compareTo(custodyService.getHotBalance()),
                "HOT 应从 1000 减至 400（100+200+300=600）");
        assertEquals(0, new BigDecimal("600").compareTo(custodyService.getColdBalance()),
                "COLD 应从 0 增至 600");
    }
}

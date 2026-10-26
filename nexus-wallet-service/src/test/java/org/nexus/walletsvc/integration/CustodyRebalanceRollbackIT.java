package org.nexus.walletsvc.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.wallet.WalletTier;
import org.nexus.sdk.wallet.WalletTransactionResult;
import org.nexus.walletsvc.custody.CustodyPolicy;
import org.nexus.walletsvc.custody.CustodyService;
import org.nexus.walletsvc.custody.CustodyTransferUnverifiedException;
import org.nexus.walletsvc.entity.CustodyBalanceEntity;
import org.nexus.walletsvc.execution.OnChainExecutionClient;
import org.nexus.walletsvc.repository.CustodyBalanceRepository;
import org.nexus.walletsvc.repository.CustodyTransferPendingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@code rebalance()} 失败语义的<b>事务级</b>验证。
 *
 * <p><b>为什么必须是集成测试</b>：{@code @Transactional} 的回滚/放行只有真实事务管理器
 * 才能体现。在 {@code DefaultCustodyServiceTest}（纯 Mockito）里，{@code rebalance()}
 * 只是普通方法调用，"余额有没有被回滚"无从观察 —— 那里只能断言"异常是否传播"。</p>
 *
 * <p><b>被测缺陷（2026-10-26）</b>：修复前 {@code rebalance()} 用
 * {@code catch (Exception e) { log.warn(...) }} 吞掉所有转账异常。而
 * {@code depositToCold} 的顺序是「先改冷热余额 → 再调链上执行」，
 * 于是执行通道返回确定失败时：异常被吞 ⇒ 事务照常提交 ⇒
 * <b>钱没出去、账却动了</b>（余额虚增）。</p>
 *
 * <p><b>本类刻意不加类级 {@code @Transactional}</b>：那会让测试方法自身开启外层事务，
 * 被测方法的提交/回滚语义会被外层事务吞掉，测不出真实行为。改用
 * {@code @AfterEach} 显式清理（与 {@code CustodyTransferPendingIntegrationTest} 一致）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(CustodyRebalanceRollbackIT.PolicyConfig.class)
class CustodyRebalanceRollbackIT {

    /**
     * 提供 {@link CustodyPolicy} bean。
     *
     * <p>生产代码里<b>没有任何 {@code CustodyPolicy} 实现类</b>（只有接口），
     * 因此 {@code rebalance()} 在线上恒走 "no policy configured" 短路。
     * 本测试要验证的是「一旦有人注册策略之后」的语义，故在此注入一个真实实例。</p>
     *
     * <p>用 {@code @TestConfiguration} 而不是 {@code @MockitoBean}：{@code CustodyPolicy}
     * 的 getter 参与判断逻辑，mock 会返回默认值使分支不可控。</p>
     */
    @TestConfiguration
    static class PolicyConfig {
        @Bean
        CustodyPolicy testCustodyPolicy() {
            CustodyPolicy policy = new CustodyPolicy(
                    new BigDecimal("5000"),    // hotWalletCap
                    new BigDecimal("20000"),   // warmWalletCap
                    new BigDecimal("3000"));   // autoSweepThreshold
            policy.setColdWalletCap(new BigDecimal("20000"));
            policy.setHotWalletFloor(new BigDecimal("500"));
            return policy;
        }
    }

    @Autowired
    private CustodyService custodyService;

    @Autowired
    private CustodyBalanceRepository custodyBalanceRepository;

    @Autowired
    private CustodyTransferPendingRepository pendingRepository;

    /** 替换真实 HttpOnChainExecutionClient：按用例返回指定执行结果。 */
    @MockitoBean
    private OnChainExecutionClient executionClient;

    @BeforeEach
    void seedBalances() {
        // hot=4000 > autoSweepThreshold=3000 ⇒ 会 sweep 1000 到冷钱包
        setBalances(new BigDecimal("4000"), new BigDecimal("1000"));
    }

    @AfterEach
    void cleanup() {
        pendingRepository.deleteAll();
        setBalances(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void setBalances(BigDecimal hot, BigDecimal cold) {
        CustodyBalanceEntity hotEntity = custodyBalanceRepository.findByTier("HOT").orElseThrow();
        hotEntity.setBalance(hot);
        custodyBalanceRepository.save(hotEntity);
        CustodyBalanceEntity coldEntity = custodyBalanceRepository.findByTier("COLD").orElseThrow();
        coldEntity.setBalance(cold);
        custodyBalanceRepository.save(coldEntity);
    }

    @Test
    @DisplayName("sweep 确定失败：事务回滚，余额复原（修复前会留下「钱没出、账已动」）")
    void sweepFailed_rollsBackBalances() {
        when(executionClient.execute(any())).thenReturn(new WalletTransactionResult(
                null, WalletTransactionResult.Status.FAILED, 0, "gateway unreachable", false));

        assertThrows(IllegalStateException.class,
                () -> custodyService.rebalance(WalletTier.COLD));

        // 关键断言：余额必须回到 sweep 之前。
        // 修复前这里是 3000 / 2000（异常被吞、事务提交），即「钱没出去但账动了」。
        assertEquals(0, new BigDecimal("4000").compareTo(custodyService.getHotBalance()),
                "确定失败后热钱包余额必须回滚到 4000");
        assertEquals(0, new BigDecimal("1000").compareTo(custodyService.getColdBalance()),
                "确定失败后冷钱包余额必须回滚到 1000");
    }

    @Test
    @DisplayName("sweep 已执行但不可核对：事务提交，余额保留 + 登记 VERIFY_REQUIRED")
    void sweepUnverified_keepsBalancesAndRecordsPending() {
        when(executionClient.execute(any())).thenReturn(new WalletTransactionResult(
                null, WalletTransactionResult.Status.EXECUTED_UNVERIFIED, 0, "no hash", false, "APR-1"));

        assertThrows(CustodyTransferUnverifiedException.class,
                () -> custodyService.rebalance(WalletTier.COLD));

        // 钱可能已出 ⇒ 余额必须保留（noRollbackFor 生效）
        assertEquals(0, new BigDecimal("3000").compareTo(custodyService.getHotBalance()),
                "已执行但不可核对时，热钱包余额必须保留（不回滚）");
        assertEquals(0, new BigDecimal("2000").compareTo(custodyService.getColdBalance()),
                "已执行但不可核对时，冷钱包余额必须保留（不回滚）");
        // 且必须留痕，供人工核对
        assertEquals(1, pendingRepository.findAll().size(),
                "必须登记一条 VERIFY_REQUIRED 待处置记录");
    }

    @Test
    @DisplayName("sweep 成功：余额按 sweep 调整（对照组，确认改动没有破坏正常路径）")
    void sweepSuccess_balancesUpdated() {
        when(executionClient.execute(any())).thenReturn(new WalletTransactionResult(
                "0xABC123", WalletTransactionResult.Status.SUCCESS, 1, null, false));

        custodyService.rebalance(WalletTier.COLD);

        assertEquals(0, new BigDecimal("3000").compareTo(custodyService.getHotBalance()));
        assertEquals(0, new BigDecimal("2000").compareTo(custodyService.getColdBalance()));
        assertEquals(0, pendingRepository.findAll().size(),
                "成功路径不应产生待处置记录");
    }
}

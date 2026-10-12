package org.nexus.gateway.subscription;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 周期扣款的"同一账期只扣一次"守卫（缺陷探针转正的回归用例，2026-10-13）。
 *
 * <p>背景：v2 订阅体系（表 {@code subscription_v2}）的自动扣款由
 * {@link BillingCycleScheduler#processDueSubscriptions()} 每小时整点触发，
 * 落到 {@link DefaultSubscriptionService#processBillingCycle(String)}。
 * 而 prod 部署是 {@code nexus-gateway.replicaCount: 3}（values-prod.yaml:20-21），
 * 该方法只校验 status、<b>既不校验 nextChargeAt 是否到期、也没有任何并发认领</b>，
 * 调度方法上也没有 ShedLock —— 三条里缺任一条，同一账期都可能被扣多次
 * （扣款走 ChargeExecutor → connector.createPayment，是不可逆的真实动账）。</p>
 *
 * <p>对照：v1 体系 {@code SubscriptionServiceImpl#charge} 已经用
 * {@code claimTemplate + SubscriptionRepository.claimCharge}（条件 UPDATE，
 * {@code WHERE ... nextChargeAt <= :now}）做了"先原子认领再动账"，
 * 并在注释里写明"认领已提交，链上转账在事务外执行（不可逆操作不入事务）"。
 * v2 这条路径没跟上。</p>
 *
 * <p>用手工 mock 而非 {@code @Mock + MockitoExtension}：部分用例故意不 stub
 * （严格桩会因"未使用"报错，诱导人去塞空断言）。</p>
 */
class BillingCycleSingleChargeGuardTest {

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionPlanRepository planRepository;
    private ChargeExecutor chargeExecutor;
    private DefaultSubscriptionService service;
    private final java.util.concurrent.atomic.AtomicInteger charges =
            new java.util.concurrent.atomic.AtomicInteger();

    private static Subscription subscription(String id, LocalDateTime nextChargeAt,
                                             SubscriptionStatus status) {
        Subscription s = new Subscription();
        s.setSubscriptionId(id);
        s.setMerchantId(100L);
        s.setCustomerId("cust-1");
        s.setPayerAddress("0xPayer");
        s.setPayeeAddress("0xPayee");
        s.setPlanId("plan-basic");
        s.setStatus(status);
        s.setCurrentPeriodStart(nextChargeAt.minusDays(30));
        s.setCurrentPeriodEnd(nextChargeAt);
        s.setNextChargeAt(nextChargeAt);
        s.setDunningCount(0);
        s.setChargedCount(0);
        return s;
    }

    private static SubscriptionPlan basicPlan() {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanId("plan-basic");
        p.setName("Basic");
        p.setAmount(new BigDecimal("19.90"));
        p.setBillingPeriod(BillingPeriod.MONTHLY);
        p.setTrialPeriodDays(0);
        p.setCurrency("NEX");
        p.setEnabled(true);
        return p;
    }

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        planRepository = mock(SubscriptionPlanRepository.class);
        chargeExecutor = mock(ChargeExecutor.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        when(planRepository.findByPlanId("plan-basic")).thenReturn(Optional.of(basicPlan()));
        when(chargeExecutor.charge(any(), any(), anyString())).thenAnswer(inv -> {
            charges.incrementAndGet();
            return ChargeResult.success("0xTx", "chain");
        });
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new DefaultSubscriptionService(subscriptionRepository, planRepository,
                chargeExecutor, new DunningManager(publisher, new SubscriptionProperties()),
                new ProrationCalculator());
    }

    @Test
    @DisplayName("未到账期（nextChargeAt 在未来）：不得扣款 —— 这是当前缺失的守卫")
    void notDueSubscriptionMustNotBeCharged() {
        LocalDateTime future = LocalDateTime.now().plusMinutes(5);
        when(subscriptionRepository.findBySubscriptionId("SUB-EARLY"))
                .thenReturn(Optional.of(subscription("SUB-EARLY", future, SubscriptionStatus.ACTIVE)));

        String txHash = service.processBillingCycle("SUB-EARLY");

        assertEquals(0, charges.get(),
                "未到账期就扣款 = 提前向客户真实扣款；processBillingCycle 必须自己校验 nextChargeAt，"
                        + "不能只依赖调度侧的筛选（多副本 / 手工触发 / 扫描重叠都会绕过筛选）");
        assertNull(txHash, "未扣款就不该返回交易哈希");
    }

    @Test
    @DisplayName("已到期且 ACTIVE：正常扣款一次并推进账期（守住 happy path，别把修复做成「不能扣款」）")
    void dueSubscriptionIsChargedOnceAndPeriodAdvances() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(1);
        Subscription sub = subscription("SUB-DUE", past, SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findBySubscriptionId("SUB-DUE")).thenReturn(Optional.of(sub));

        String txHash = service.processBillingCycle("SUB-DUE");

        assertEquals("0xTx", txHash);
        assertEquals(1, charges.get());
        assertEquals(1, sub.getChargedCount(), "扣款成功应记一次账");
        assertTrue(sub.getNextChargeAt().isAfter(past), "账期应推进到下一个周期");
    }

    @Test
    @DisplayName("整点扫描必须被 ShedLock 保护：prod 3 副本同分钟各扫一遍就是各扣一遍")
    void sweepMustCarrySchedulerLock() throws NoSuchMethodException {
        Method m = BillingCycleScheduler.class.getMethod("processDueSubscriptions");

        assertTrue(m.isAnnotationPresent(SchedulerLock.class),
                " BillingCycleScheduler.processDueSubscriptions 缺 @SchedulerLock："
                        + " values-prod.yaml 的 nexus-gateway.replicaCount=3，三个副本会在同一整点"
                        + "各自扫描并各自扣款。同仓已有先例（ReconciliationTask、RoutingAuditService）"
                        + "且 @EnableSchedulerLock 已在 GatewayApplication 启用");

        SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
        assertNotNull(lock.name(), "@SchedulerLock 必须有锁名");
        assertFalse(lock.lockAtMostFor().isEmpty(),
                "必须显式 lockAtMostFor：副本崩溃后锁要能自动释放，否则整点扫描永久停摆");
        assertFalse(lock.lockAtLeastFor().isEmpty(),
                "必须显式 lockAtLeastFor：防止快速重启的副本在同一分钟内重复执行");
    }
}

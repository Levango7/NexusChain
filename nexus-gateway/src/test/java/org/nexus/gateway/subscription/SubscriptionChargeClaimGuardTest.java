package org.nexus.gateway.subscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.nexus.gateway.subscription.claim.SubscriptionChargeClaimService;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 订阅周期扣款的"先原子认领、后动账"接线（缺陷探针，2026-10-13）。
 *
 * <p>缺陷：v2 扣款（{@link DefaultSubscriptionService#processBillingCycle} /
 * {@link DefaultSubscriptionService#convertTrialToActive}）在动账前只有
 * status + {@code nextChargeAt} 到期校验（check-then-act），且 {@code Subscription}
 * 实体<b>无 {@code @Version}</b>。prod 三副本（values-prod.yaml:20-21）或
 * "整点扫描 + 手工触发"并发时，两个执行方都能读到"已到期"并各调一次
 * {@link ChargeExecutor#charge}（→ connector.createPayment，不可逆真实动账）。</p>
 *
 * <p>对照 v1 体系 {@code SubscriptionServiceImpl#charge}：claimTemplate +
 * 条件 UPDATE 原子认领，"认领已提交，链上转账在事务外执行"。v2 没跟上。
 * 修复即"认领未获批就不动账"——本用例钉住这条接线。</p>
 *
 * <p><b>诚实的边界</b>：本用例用 mock 的 {@link SubscriptionChargeClaimService}
 * 证明的是<b>接线</b>（服务是否咨询认领、是否尊重"被拒"），不是并发裁决本身；
 * 唯一键在真实数据库上的裁决由 {@code SubscriptionChargeClaimSchemaIT} 用真 H2
 * 唯一约束验证。两者缺一不可。</p>
 */
class SubscriptionChargeClaimGuardTest {

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionPlanRepository planRepository;
    private ChargeExecutor chargeExecutor;
    private SubscriptionChargeClaimService claimService;
    private DefaultSubscriptionService service;
    private final AtomicInteger charges = new AtomicInteger();

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
        claimService = mock(SubscriptionChargeClaimService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        when(planRepository.findByPlanId("plan-basic")).thenReturn(Optional.of(basicPlan()));
        when(chargeExecutor.charge(any(), any(), anyString())).thenAnswer(inv -> {
            charges.incrementAndGet();
            return ChargeResult.success("0xTx", "chain");
        });
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new DefaultSubscriptionService(subscriptionRepository, planRepository,
                chargeExecutor, new DunningManager(publisher, new SubscriptionProperties()),
                new ProrationCalculator(), claimService);
    }

    @Test
    @DisplayName("认领被别的执行方抢走：本执行方不得动账、不得推进账期")
    void claimDeniedMeansNoCharge() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        Subscription sub = subscription("SUB-RACE", due, SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findBySubscriptionId("SUB-RACE")).thenReturn(Optional.of(sub));
        when(claimService.tryClaim("SUB-RACE", due.toString())).thenReturn(false);

        String txHash = service.processBillingCycle("SUB-RACE");

        assertEquals(0, charges.get(),
                "认领被拒（同一账期已被其他副本认领）却仍然动账 = 并发重复扣款又回来了；"
                        + "processBillingCycle 必须以认领结果为闸门");
        assertNull(txHash, "未认领成功就不该返回交易哈希");
        assertEquals(0, sub.getChargedCount(), "被拒的这次不应记一笔账");
    }

    @Test
    @DisplayName("认领获批：正常扣款一次并推进账期（守住 happy path，别把修复做成「不能扣款」）")
    void claimGrantedIsChargedOnceAndPeriodAdvances() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        Subscription sub = subscription("SUB-OK", due, SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findBySubscriptionId("SUB-OK")).thenReturn(Optional.of(sub));
        when(claimService.tryClaim("SUB-OK", due.toString())).thenReturn(true);

        String txHash = service.processBillingCycle("SUB-OK");

        assertEquals("0xTx", txHash);
        assertEquals(1, charges.get());
        assertEquals(1, sub.getChargedCount(), "扣款成功应记一次账");
        assertTrue(sub.getNextChargeAt().isAfter(due), "账期应推进到下一个周期");
    }

    @Test
    @DisplayName("认领键 = 本次到期的 nextChargeAt，且认领必须发生在动账之前")
    void claimKeyIsDueTimestampAndHappensBeforeCharge() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1);
        Subscription sub = subscription("SUB-ORDER", due, SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findBySubscriptionId("SUB-ORDER")).thenReturn(Optional.of(sub));
        when(claimService.tryClaim(anyString(), anyString())).thenReturn(true);

        service.processBillingCycle("SUB-ORDER");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sid = ArgumentCaptor.forClass(String.class);
        verify(claimService).tryClaim(sid.capture(), key.capture());
        assertEquals("SUB-ORDER", sid.getValue());
        assertEquals(due.toString(), key.getValue(),
                "认领键必须锚定本次到期的账期点（nextChargeAt），否则并发重复无法共享同一 key 而被裁决");

        InOrder ordered = inOrder(claimService, chargeExecutor);
        ordered.verify(claimService).tryClaim(anyString(), anyString());
        ordered.verify(chargeExecutor).charge(any(), any(), anyString());
    }

    @Test
    @DisplayName("试用转正的首扣同属真实动账：认领被拒时不得扣款、也不得宣告转正")
    void trialFirstChargeAlsoGatedByClaim() {
        LocalDateTime trialEnd = LocalDateTime.now().minusMinutes(1);
        Subscription sub = subscription("SUB-TRIAL", trialEnd, SubscriptionStatus.TRIAL);
        sub.setTrialEnd(trialEnd);
        when(subscriptionRepository.findBySubscriptionId("SUB-TRIAL")).thenReturn(Optional.of(sub));
        when(claimService.tryClaim("SUB-TRIAL", trialEnd.toString())).thenReturn(false);

        boolean converted = service.convertTrialToActive("SUB-TRIAL");

        assertEquals(0, charges.get(),
                "试用转正的首次扣款也是不可逆动账，认领被拒时同样不得扣款");
        assertFalse(converted, "未认领成功就不该宣告转正（否则账期状态与资金双写不一致）");
    }
}

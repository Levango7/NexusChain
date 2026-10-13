package org.nexus.gateway.escrow;

import jakarta.persistence.Version;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 担保/预授权超时调度的多副本防护（缺陷探针，2026-10-13）。
 *
 * <p>prod 部署是 {@code nexus-gateway.replicaCount: 3}（values-prod.yaml:20-21），
 * 而 {@link EscrowTimeoutScheduler} 的两个 5 分钟扫描方法都<b>没有 {@code @SchedulerLock}</b>
 * —— 同仓既有先例（{@code ReconciliationScheduler#reconcile}、{@code RoutingAuditService}），
 * 且 {@code @EnableSchedulerLock} 已在 {@code GatewayApplication} 启用，
 * 所以这不是"项目不用 ShedLock"，是这两个方法漏挂。</p>
 *
 * <p>诚实边界（本用例刻意不夸大的部分）：类注释宣称"通过状态校验保证幂等"
 * （{@code EscrowTimeoutScheduler:20-21}），那是 check-then-act，不是原子认领；
 * 真正兜住"同一笔被两个副本各放一次款"的是实体上的 {@code @Version} 乐观锁
 * （{@code EscrowTransaction:62-64}、{@code PreAuthTransaction:63-65}）——
 * 动账与状态写回在同一条 {@code @Transactional} 里，抢输的一方整体回滚。
 * 所以这里的断言是"扫描层不去重 ⇒ 缺锁就是白做 N-1 遍并反复触发乐观锁冲突/重复事件尝试"，
 * 而不是"缺锁就直接双付"。{@link #entitiesCarryOptimisticVersionGuard()} 把这条承重结构钉住：
 * 一旦有人摘掉 {@code @Version}，缺锁会立刻升级成真实资金事故，此用例先替我们喊出来。</p>
 */
@ExtendWith(MockitoExtension.class)
class EscrowTimeoutSchedulerLockTest {

    @Mock
    private EscrowTransactionRepository escrowRepository;

    @Mock
    private PreAuthTransactionRepository preauthRepository;

    @Mock
    private EscrowService escrowService;

    @Mock
    private PreAuthService preAuthService;

    private EscrowTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EscrowTimeoutScheduler(escrowRepository, preauthRepository,
                escrowService, preAuthService);
    }

    private static EscrowTransaction fundedEscrow(String escrowNo) {
        EscrowTransaction escrow = new EscrowTransaction();
        escrow.setEscrowNo(escrowNo);
        escrow.setMerchantId(500L);
        escrow.setAmount(new BigDecimal("120.00"));
        escrow.setStatus(EscrowStatus.FUNDED);
        escrow.setFundedAt(LocalDateTime.now().minusDays(30));
        escrow.setAutoConfirmDays(7);
        return escrow;
    }

    private static void assertLockDeclared(Method method, String label) {
        assertTrue(method.isAnnotationPresent(SchedulerLock.class),
                label + " 缺 @SchedulerLock：values-prod.yaml 的 nexus-gateway.replicaCount=3，"
                        + "三个副本会在同一个 5 分钟 tick 各自扫描、各自尝试动账。"
                        + "同仓已有先例（ReconciliationScheduler#reconcile）且 @EnableSchedulerLock 已启用");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertNotNull(lock.name(), label + " 的 @SchedulerLock 必须有锁名");
        assertFalse(lock.lockAtMostFor().isEmpty(),
                label + " 必须显式 lockAtMostFor：副本崩溃后锁要能自动释放，否则超时释放永久停摆");
        assertFalse(lock.lockAtLeastFor().isEmpty(),
                label + " 必须显式 lockAtLeastFor：防止快速重启的副本在同一 tick 内重复执行");
    }

    @Test
    @DisplayName("担保超时自动确认 — 必须挂 @SchedulerLock")
    void autoConfirmEscrowMustCarrySchedulerLock() throws NoSuchMethodException {
        assertLockDeclared(EscrowTimeoutScheduler.class.getMethod("autoConfirmEscrow"),
                "EscrowTimeoutScheduler.autoConfirmEscrow");
    }

    @Test
    @DisplayName("预授权超时自动释放 — 必须挂 @SchedulerLock")
    void autoReleasePreAuthMustCarrySchedulerLock() throws NoSuchMethodException {
        assertLockDeclared(EscrowTimeoutScheduler.class.getMethod("autoReleasePreAuth"),
                "EscrowTimeoutScheduler.autoReleasePreAuth");
    }

    @Test
    @DisplayName("承重结构 — 两个实体必须保留 @Version 乐观锁（它是当前唯一的双跑防线）")
    void entitiesCarryOptimisticVersionGuard() {
        assertTrue(hasVersionField(EscrowTransaction.class),
                "EscrowTransaction 丢了 @Version：扫描层不去重，缺 @SchedulerLock 时"
                        + "两个副本就真的会各放一次款（confirmEscrow 的 unfreeze+deposit 与状态写回同事务）");
        assertTrue(hasVersionField(PreAuthTransaction.class),
                "PreAuthTransaction 丢了 @Version：同上，voidPreAuth 会重复释放冻结金额");
    }

    @Test
    @DisplayName("扫描层不去重 — 同一笔连跑两次 sweep 就被尝试两次（所以锁必须挂在入口）")
    void sweepItselfDoesNotDedupe() {
        EscrowTransaction escrow = fundedEscrow("ESC-LOCK-1");
        when(escrowRepository.findByStatusAndFundedAtBefore(any(), any()))
                .thenReturn(List.of(escrow));

        scheduler.autoConfirmEscrow();
        scheduler.autoConfirmEscrow();

        // 这里断言的是"没有认领"这一事实（串行两次即成立），不拿它当并发双付的证据。
        verify(escrowService, times(2)).confirmEscrow("ESC-LOCK-1");
    }

    private static boolean hasVersionField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Version.class)) {
                return true;
            }
        }
        return false;
    }
}

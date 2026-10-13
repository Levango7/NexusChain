package org.nexus.gateway.reconciliation.link;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 交易对账审计调度的多副本防护（缺陷探针，2026-10-13）。
 *
 * <p>prod 是 {@code nexus-gateway.replicaCount: 3}（values-prod.yaml:20-21），
 * 而 {@link TransactionAuditScheduler#dailyAudit()} 是每日 02:00 的裸 {@code @Scheduled}
 * —— 三个副本会在同一刻各自全量审计一遍。</p>
 *
 * <p><b>严重性诚实边界（本用例刻意不夸大的部分）</b>：下钻 {@code TransactionAuditService#executeAudit}
 * 后确认它<b>只读</b>差错记录、<b>只写</b> {@code transaction_audit_records} 审计报告行，
 * <b>不触发任何资金动作</b>（无调账、无划拨）。所以缺锁的后果是
 * <b>同一天出现多份重复审计报告</b>（V62 迁移只建了 {@code idx_tar_audit_date} 与
 * {@code idx_tar_audit_date_merchant} 两个<b>非唯一</b>索引，没有任何约束挡住重复行），
 * 是<b>报告完整性</b>问题，不是资金事故。修法上锁是与之最相称的最小改法。</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionAuditSchedulerLockTest {

    @Mock
    private TransactionAuditService transactionAuditService;

    private TransactionAuditScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TransactionAuditScheduler(transactionAuditService);
    }

    @Test
    @DisplayName("每日审计入口必须挂 @SchedulerLock（三副本各审一遍 = 三份重复报告）")
    void dailyAuditMustCarrySchedulerLock() throws NoSuchMethodException {
        Method method = TransactionAuditScheduler.class.getMethod("dailyAudit");

        assertTrue(method.isAnnotationPresent(SchedulerLock.class),
                "TransactionAuditScheduler.dailyAudit 缺 @SchedulerLock：values-prod.yaml 的 "
                        + "nexus-gateway.replicaCount=3，三个副本会在 02:00 各自全量审计一遍，"
                        + "而 V62 的索引都不是唯一索引，重复报告行不会被 DB 挡住。"
                        + "同仓先例：DailyReconciliationScheduler#runDailyReconciliation（同为每日 cron 已上锁）");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertNotNull(lock.name(), "必须有锁名");
        assertFalse(lock.lockAtMostFor().isEmpty(),
                "必须显式 lockAtMostFor：副本崩溃后锁要能自动释放，否则每日审计永久停摆");
        assertFalse(lock.lockAtLeastFor().isEmpty(),
                "必须显式 lockAtLeastFor：防止快速重启的副本在同一 tick 内重复执行");
    }

    @Test
    @DisplayName("调度入口自身不去重 — 连跑两次就审计两次（所以锁必须挂在入口）")
    void schedulerItselfDoesNotDedupe() {
        when(transactionAuditService.executeAudit(any(LocalDate.class), any()))
                .thenReturn(Collections.emptyList());

        scheduler.dailyAudit();
        scheduler.dailyAudit();

        // 断言"入口没有认领/去重"这一事实；串行两次即成立，不当并发证据。
        verify(transactionAuditService, times(2)).executeAudit(any(LocalDate.class), any());
    }

    @Test
    @DisplayName("审计目标固定为「前一自然日 + 全量商户」")
    void auditTargetsPreviousDayForAllMerchants() {
        when(transactionAuditService.executeAudit(any(LocalDate.class), any()))
                .thenReturn(Collections.emptyList());

        scheduler.dailyAudit();

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Long> merchantCaptor = ArgumentCaptor.forClass(Long.class);
        verify(transactionAuditService).executeAudit(dateCaptor.capture(), merchantCaptor.capture());

        assertEquals(LocalDate.now().minusDays(1), dateCaptor.getValue(), "应审计前一自然日");
        assertNull(merchantCaptor.getValue(), "merchantId=null 表示全量审计");
    }
}

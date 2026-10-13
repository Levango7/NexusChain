package org.nexus.gateway.clearing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算划拨指令的"先认领再动账"探针（缺陷探针转正的回归用例，2026-10-13）。
 *
 * <p>两个事实合起来构成这条链上的地雷：</p>
 * <ol>
 *   <li>{@link SettlementTransferScheduler} 的类注释（:18）写着"使用 ShedLock 保证多实例部署时
 *       同一时刻仅一个实例执行"，但方法上<b>没有任何 {@code @SchedulerLock}</b> —— 注释与代码相反。
 *       而 {@code values-prod.yaml:20-21} 是 {@code nexus-gateway.replicaCount: 3}。</li>
 *   <li>扫描循环（:63-80）读到 PENDING 指令后，直接调
 *       {@code executeSettlementTransfer(merchantId, amount, batchNo, direction)}
 *       —— 该方法是<b>新建一条记录并动账</b>（SettlementTransferService:74-82），
 *       <b>从不回写被扫到的那条指令</b>。所以只要存在一条 PENDING 的 SETTLEMENT_TRANSFER 指令，
 *       每 5 分钟、每个副本都会再划拨一次，且新记录自带随机 reference（:85），事后无法去重。</li>
 * </ol>
 *
 * <p><b>当前可达性（不夸大的部分）</b>：本仓唯一写 {@code recordType=SETTLEMENT_TRANSFER} 的地方
 * 就是 {@code SettlementTransferService:76}，而它在同一方法内把状态推到 BOOKED/FAILED（:102/:109）；
 * {@code ClearingSettlementService:121} 写的是 {@code CLEARING}。也就是说<b>今天的扫描集恒空、
 * 这条路径不可达</b>。所以本 PR 修的是"埋着的雷"，不是"正在漏钱"——
 * 可达性判定见 PR 描述，这里用测试钉住的是"一旦有指令写入（运维插入或将来接上真实生产者）不得重复出账"。</p>
 *
 * <p>打桩一律用 {@code lenient()}：本提交（先红）里调度器还没接线，那些认领/收尾桩必然"未被使用"，
 * 严格桩会把它变成 UnnecessaryStubbingException，让红点看不出是"没接线"还是"测试写坏了"。
 * 用 lenient 保证红点恰好落在断言上。</p>
 */
@ExtendWith(MockitoExtension.class)
class SettlementTransferSweepClaimTest {

    @Mock
    private ClearingSettlementRecordRepository recordRepository;

    @Mock
    private SettlementTransferService settlementTransferService;

    private SettlementTransferScheduler scheduler;

    private static final long INSTRUCTION_ID = 77L;

    private static ClearingSettlementRecord pendingInstruction() {
        ClearingSettlementRecord record = new ClearingSettlementRecord();
        record.setId(INSTRUCTION_ID);
        record.setRecordNo("STI-77");
        record.setRecordType("SETTLEMENT_TRANSFER");
        record.setBookingStatus(BookingStatus.PENDING);
        record.setMerchantId(9L);
        record.setAmount(new BigDecimal("50.00"));
        record.setBatchNo("B-1");
        record.setDirection("CREDIT");
        record.setSettledAt(null); // null = 立即执行
        return record;
    }

    @BeforeEach
    void setUp() {
        scheduler = new SettlementTransferScheduler(recordRepository, settlementTransferService, true);
    }

    @Test
    @DisplayName("扫描入口必须挂 @SchedulerLock（类注释已经这么宣称了）")
    void sweepMustCarrySchedulerLock() throws NoSuchMethodException {
        Method method = SettlementTransferScheduler.class.getMethod("executeScheduledTransfers");

        assertTrue(method.isAnnotationPresent(SchedulerLock.class),
                "SettlementTransferScheduler.executeScheduledTransfers 缺 @SchedulerLock，"
                        + "而类注释 :18 宣称「使用 ShedLock 保证多实例部署时同一时刻仅一个实例执行」："
                        + "注释与代码相反。prod replicaCount=3，三条 5 分钟扫描会各自跑");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertFalse(lock.lockAtMostFor().isEmpty(), "必须显式 lockAtMostFor：副本崩溃后锁要能自动释放");
        assertFalse(lock.lockAtLeastFor().isEmpty(), "必须显式 lockAtLeastFor：防快速重启的副本重复执行");
    }

    @Test
    @DisplayName("同一条指令连跑两次扫描 — 只允许动账一次")
    void sameInstructionMustNotBeTransferredTwice() {
        when(recordRepository.findByRecordTypeAndBookingStatus("SETTLEMENT_TRANSFER", BookingStatus.PENDING))
                .thenReturn(List.of(pendingInstruction()));
        lenient().when(settlementTransferService.claimScheduledTransfer(INSTRUCTION_ID))
                .thenReturn(true, false);

        scheduler.executeScheduledTransfers();
        scheduler.executeScheduledTransfers();

        verify(settlementTransferService, times(1))
                .executeSettlementTransfer(eq(9L), any(BigDecimal.class), eq("B-1"), eq("CREDIT"));
    }

    @Test
    @DisplayName("认领失败（已被别的副本拿走）— 一律不得动账")
    void failedClaimMustNotMoveMoney() {
        when(recordRepository.findByRecordTypeAndBookingStatus("SETTLEMENT_TRANSFER", BookingStatus.PENDING))
                .thenReturn(List.of(pendingInstruction()));
        lenient().when(settlementTransferService.claimScheduledTransfer(INSTRUCTION_ID)).thenReturn(false);

        scheduler.executeScheduledTransfers();

        verify(settlementTransferService, never())
                .executeSettlementTransfer(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("动账成功 — 指令必须从 EXECUTING 收尾为成功态（不能永远停在 PENDING/EXECUTING）")
    void successfulTransferMustCloseTheInstruction() {
        when(recordRepository.findByRecordTypeAndBookingStatus("SETTLEMENT_TRANSFER", BookingStatus.PENDING))
                .thenReturn(List.of(pendingInstruction()));
        lenient().when(settlementTransferService.claimScheduledTransfer(INSTRUCTION_ID)).thenReturn(true);

        scheduler.executeScheduledTransfers();

        verify(settlementTransferService).finishScheduledTransfer(eq(INSTRUCTION_ID), eq(true), any());
    }

    @Test
    @DisplayName("动账抛异常 — 收尾为失败态且不外抛（调度线程安全）")
    void failedTransferMustCloseAsFailedAndNotEscape() {
        when(recordRepository.findByRecordTypeAndBookingStatus("SETTLEMENT_TRANSFER", BookingStatus.PENDING))
                .thenReturn(List.of(pendingInstruction()));
        lenient().when(settlementTransferService.claimScheduledTransfer(INSTRUCTION_ID)).thenReturn(true);
        lenient().doThrow(new IllegalStateException("渠道划拨失败"))
                .when(settlementTransferService)
                .executeSettlementTransfer(anyLong(), any(), anyString(), anyString());

        assertDoesNotThrow(() -> scheduler.executeScheduledTransfers());

        verify(settlementTransferService).finishScheduledTransfer(
                eq(INSTRUCTION_ID), eq(false), anyString());
    }
}

package org.nexus.gateway.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重启后恢复经 {@link TccActionResolver} 自动重放（#31-Step2a，#102 PLAN §4.1）。
 *
 * <p>这是 Step1（#107）的续：Step1 把注册表 miss 从"谎报 FAILED"改成"挂起转人工"；Step2 让 miss
 * 先尝试按 {@code participantId} 重建<b>无状态可重放</b> action，用持久化上下文（payload→ctx）
 * 真正跑完 Confirm/Cancel；只有重建不到才退回 PENDING_MANUAL。</p>
 */
@ExtendWith(MockitoExtension.class)
class TccRecoveryResolverTest {

    @Mock private TransactionLogRepository transactionLogRepository;

    private TccTransactionManager tccManager;
    private static final String PID = "payment-confirm";

    private static TransactionLog tryTimeoutLog() {
        TransactionLog log = new TransactionLog();
        log.setTransactionId("TX-RESTART-REPLAY");
        log.setTransactionType(TransactionType.TCC);
        log.setStepStatus(TransactionStepStatus.TRY);
        log.setParticipantId(PID);
        log.setTenantId("tenant-1");
        log.setBusinessReference("ORDER-1");
        log.setPayload("{\"orderId\":123,\"chainTxHash\":\"0xabc\",\"businessReference\":\"ORDER-1\"}");
        return log;
    }

    @BeforeEach
    void setUp() {
        tccManager = new TccTransactionManager(transactionLogRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("注册表 miss 但 resolver 命中 → 重建 action 并真正执行 Cancel，状态 SUCCESS（非 PENDING_MANUAL）")
    void registryMissButResolverHit_replaysCancelAndSucceeds() {
        // 模拟"进程重启"：新 manager，actionRegistry 为空。
        ReplayableTccAction replayable = org.mockito.Mockito.mock(ReplayableTccAction.class);
        when(replayable.getParticipantId()).thenReturn(PID);
        tccManager.setActionResolver(new TccActionResolver(List.of(replayable)));

        TransactionLog tryLog = tryTimeoutLog();
        boolean recovered = tccManager.retryCancel(tryLog);

        assertTrue(recovered, "resolver 命中且 Cancel 成功 ⇒ 恢复应判成功");
        // 关键资金断言：预留资源确实被释放（cancelAction 真被调用），而不是只挂人工。
        verify(replayable).cancelAction(any(TransactionContext.class));
        assertEquals(TransactionStepStatus.SUCCESS, tryLog.getStepStatus(),
                "重放成功必须落 SUCCESS（已释放），既不是 FAILED 谎报、也不是 PENDING_MANUAL 挂起");
    }

    @Test
    @DisplayName("resolver 已装配但 participantId 未注册 → 不重放，退回 PENDING_MANUAL")
    void resolverMiss_fallsBackToPendingManual() {
        ReplayableTccAction other = org.mockito.Mockito.mock(ReplayableTccAction.class);
        when(other.getParticipantId()).thenReturn("some-other-participant");
        tccManager.setActionResolver(new TccActionResolver(List.of(other)));

        TransactionLog tryLog = tryTimeoutLog(); // participantId = payment-confirm，未注册
        boolean recovered = tccManager.retryCancel(tryLog);

        assertEquals(false, recovered);
        assertEquals(TransactionStepStatus.PENDING_MANUAL, tryLog.getStepStatus(),
                "解析不到可重放 action 时，保持 Step1 的挂起语义，绝不自动释放未知参与方、也不标 FAILED");
    }
}

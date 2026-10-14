package org.nexus.gateway.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SAGA 恢复 miss 语义的资金安全回归（与 TCC 同切片，#102 PLAN §4.1.3）。
 *
 * <p>{@link SagaTransactionManager} 类注释自陈当前未接入生产流程（补偿全由 TCC 承担），
 * 但其 {@code retryCompensate} 的 miss 分支与 TCC 是<b>同一型缺陷</b>（重启后 stepRegistry 空 →
 * 原实现标 FAILED、谎报已补偿）。本切片同口径把它改为 {@code PENDING_MANUAL} + 告警，
 * 保持两条恢复链路语义一致；即便今日空转，将来接入即安全，不留潜伏的撒谎路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class SagaRecoveryManualHoldTest {

    @Mock private TransactionLogRepository transactionLogRepository;

    private SagaTransactionManager sagaManager;

    @BeforeEach
    void setUp() {
        // 全新 manager：stepRegistry 为空，模拟进程重启后 steps 丢失。
        sagaManager = new SagaTransactionManager(transactionLogRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("retryCompensate 注册表 miss → PENDING_MANUAL，不得标 FAILED 假装已补偿")
    void retryCompensateRegistryMiss_becomesPendingManualNotFailed() {
        TransactionLog pendingLog = new TransactionLog();
        pendingLog.setTransactionId("TX-SAGA-MISS");
        pendingLog.setTransactionType(TransactionType.SAGA);
        pendingLog.setStepStatus(TransactionStepStatus.PENDING);
        pendingLog.setStepName("lockInventory");
        pendingLog.setParticipantId("saga-coordinator");
        pendingLog.setBusinessReference("ORDER-SAGA-RESTART-1");

        boolean recovered = sagaManager.retryCompensate(pendingLog);

        assertFalse(recovered);
        assertEquals(TransactionStepStatus.PENDING_MANUAL, pendingLog.getStepStatus(),
                "steps 不在注册表：应挂起转人工，而非 FAILED 谎报『已补偿』");
        assertNotEquals(TransactionStepStatus.FAILED, pendingLog.getStepStatus());
        verify(transactionLogRepository, times(1)).save(pendingLog);
    }
}

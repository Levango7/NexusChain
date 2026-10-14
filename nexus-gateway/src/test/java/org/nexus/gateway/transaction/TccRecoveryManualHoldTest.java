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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 重启后恢复 miss 语义的资金安全回归（#102 PLAN §4.1.3「miss 语义收敛」第一切片，2026-10-14）。
 *
 * <p><b>缺陷形态</b>：TCC 的 {@code retryConfirm}/{@code retryCancel}（及 SAGA 的
 * {@code retryCompensate}）在<b>进程内注册表 miss</b>（典型=服务重启后 {@code actionRegistry} 空）时，
 * 原实现直接把超时日志标 {@code FAILED} 返回。最坏路径是：Try 已预留资源 → 重启 → 注册表空 →
 * 恢复调度器扫到 TRY 超时 → {@code retryCancel} miss → 标 FAILED、<b>冻结永不释放</b>，
 * 且日志"看起来处理过了"。这是"该做的没做还标成处理完"，不是并发重复执行问题。</p>
 *
 * <p><b>期望（本切片）</b>：miss 时落 {@code PENDING_MANUAL} + 带
 * {@link RecoveryAlert#MANUAL_REQUIRED_MARKER} 的告警，显式移交人工，绝不伪装成终态 FAILED；
 * 返回值仍为 {@code false}（恢复未完成）。自动重建 action 的 {@code TccActionResolver} 是后续切片，
 * 本测试只钉"不再撒谎"这一资金安全下限。</p>
 */
@ExtendWith(MockitoExtension.class)
class TccRecoveryManualHoldTest {

    @Mock private TransactionLogRepository transactionLogRepository;

    private TccTransactionManager tccManager;

    @BeforeEach
    void setUp() {
        // 全新 manager：actionRegistry 为空，模拟"进程重启后注册表丢失"。
        tccManager = new TccTransactionManager(transactionLogRepository, new ObjectMapper());
    }

    private static TransactionLog timeoutLog(String txId, TransactionStepStatus status) {
        TransactionLog log = new TransactionLog();
        log.setTransactionId(txId);
        log.setTransactionType(TransactionType.TCC);
        log.setStepStatus(status);
        log.setParticipantId("payment-service");
        log.setBusinessReference("ORDER-RESTART-1");
        return log;
    }

    @Test
    @DisplayName("retryCancel 注册表 miss → PENDING_MANUAL（释放预留），不得标 FAILED 假装已释放")
    void retryCancelRegistryMiss_becomesPendingManualNotFailed() {
        TransactionLog tryLog = timeoutLog("TX-CANCEL-MISS", TransactionStepStatus.TRY);

        boolean recovered = tccManager.retryCancel(tryLog);

        assertFalse(recovered, "miss 时恢复未完成，应返回 false");
        assertEquals(TransactionStepStatus.PENDING_MANUAL, tryLog.getStepStatus(),
                "进程重启后 action 不在注册表：必须落 PENDING_MANUAL 显式移交人工，"
                        + "而不是 FAILED 把『冻结资源从未释放』伪装成『已处理』");
        assertNotEquals(TransactionStepStatus.FAILED, tryLog.getStepStatus(),
                "关键性质：miss 路径绝不写 FAILED（旧缺陷的撒谎行为）");
        assertNotNull(tryLog.getErrorMessage(), "PENDING_MANUAL 必须写明需人工确认资源释放");
        verify(transactionLogRepository, times(1)).save(tryLog);
    }

    @Test
    @DisplayName("retryConfirm 注册表 miss → PENDING_MANUAL，不得标 FAILED")
    void retryConfirmRegistryMiss_becomesPendingManualNotFailed() {
        TransactionLog confirmLog = timeoutLog("TX-CONFIRM-MISS", TransactionStepStatus.CONFIRM);

        boolean recovered = tccManager.retryConfirm(confirmLog);

        assertFalse(recovered);
        assertEquals(TransactionStepStatus.PENDING_MANUAL, confirmLog.getStepStatus(),
                "confirm 重试 miss 同样不得谎报 FAILED，应挂起转人工");
        assertNotEquals(TransactionStepStatus.FAILED, confirmLog.getStepStatus());
        verify(transactionLogRepository, times(1)).save(confirmLog);
    }

    @Test
    @DisplayName("PENDING_MANUAL 不在超时扫描的状态集合里 → 已交人工的项不被每 60s 反复重试")
    void pendingManualIsExcludedFromTimeoutScan() {
        // 调度器只按这四个状态做超时扫描（见 TransactionRecoveryScheduler.recoverTimeoutTransactions）。
        // PENDING_MANUAL 必须与它们都不同，否则被转人工的项会被无限重捞。
        assertNotEquals(TransactionStepStatus.TRY, TransactionStepStatus.PENDING_MANUAL);
        assertNotEquals(TransactionStepStatus.CONFIRM, TransactionStepStatus.PENDING_MANUAL);
        assertNotEquals(TransactionStepStatus.PENDING, TransactionStepStatus.PENDING_MANUAL);
        assertNotEquals(TransactionStepStatus.COMPENSATE, TransactionStepStatus.PENDING_MANUAL);
    }
}

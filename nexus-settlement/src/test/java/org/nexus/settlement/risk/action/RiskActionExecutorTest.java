package org.nexus.settlement.risk.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.settlement.risk.RiskDecision;
import org.nexus.settlement.risk.RiskScoreResult;
import org.nexus.settlement.risk.RiskTransaction;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RiskActionExecutor} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RiskActionExecutorTest {

    @Mock
    private RiskActionRecordRepository recordRepository;

    @InjectMocks
    private RiskActionExecutor executor;

    private RiskTransaction transaction;
    private RiskScoreResult scoreResult;

    @BeforeEach
    void setUp() {
        transaction = new RiskTransaction();
        transaction.setMerchantId(1L);
        transaction.setPayerAddress("0xABC");
        transaction.setAmount(BigDecimal.TEN);

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("IP_SCORE", 80);
        scores.put("BLACKLIST_SCORE", 0);
        scoreResult = RiskScoreResult.of(40, RiskDecision.PENDING_REVIEW, scores, Map.of());
    }

    @Test
    void executeAction_alert_shouldReturnApproved() {
        when(recordRepository.save(any(RiskActionRecord.class))).thenAnswer(invocation -> {
            RiskActionRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        RiskDecision decision = executor.executeAction(RiskActionRecord.ActionType.ALERT, scoreResult, transaction);

        assertEquals(RiskDecision.APPROVED, decision);
        verify(recordRepository).save(any(RiskActionRecord.class));
    }

    @Test
    void executeAction_block_shouldReturnRejected() {
        when(recordRepository.save(any(RiskActionRecord.class))).thenAnswer(invocation -> {
            RiskActionRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        RiskDecision decision = executor.executeAction(RiskActionRecord.ActionType.BLOCK, scoreResult, transaction);

        assertEquals(RiskDecision.REJECTED, decision);
        verify(recordRepository).save(any(RiskActionRecord.class));
    }

    @Test
    void executeAction_manualReview_shouldReturnPendingReview() {
        when(recordRepository.save(any(RiskActionRecord.class))).thenAnswer(invocation -> {
            RiskActionRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        RiskDecision decision = executor.executeAction(RiskActionRecord.ActionType.MANUAL_REVIEW, scoreResult, transaction);

        assertEquals(RiskDecision.PENDING_REVIEW, decision);
        verify(recordRepository).save(any(RiskActionRecord.class));
    }

    @Test
    void executeAction_capture_shouldReturnApproved() {
        when(recordRepository.save(any(RiskActionRecord.class))).thenAnswer(invocation -> {
            RiskActionRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        RiskDecision decision = executor.executeAction(RiskActionRecord.ActionType.CAPTURE, scoreResult, transaction);

        assertEquals(RiskDecision.APPROVED, decision);
        verify(recordRepository).save(any(RiskActionRecord.class));
    }

    @Test
    void executeAction_nullActionType_shouldReturnApproved() {
        RiskDecision decision = executor.executeAction(null, scoreResult, transaction);

        assertEquals(RiskDecision.APPROVED, decision);
        verify(recordRepository, never()).save(any());
    }

    @Test
    void executeAction_nullScoreResult_shouldNotThrow() {
        when(recordRepository.save(any(RiskActionRecord.class))).thenAnswer(invocation -> {
            RiskActionRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        RiskDecision decision = executor.executeAction(RiskActionRecord.ActionType.BLOCK, null, transaction);

        assertEquals(RiskDecision.REJECTED, decision);
    }

    @Test
    void executeAction_nullTransaction_shouldNotThrow() {
        when(recordRepository.save(any(RiskActionRecord.class))).thenAnswer(invocation -> {
            RiskActionRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        RiskDecision decision = executor.executeAction(RiskActionRecord.ActionType.ALERT, scoreResult, null);

        assertEquals(RiskDecision.APPROVED, decision);
    }

    @Test
    void mapActionToDecision_shouldMapCorrectly() {
        assertEquals(RiskDecision.APPROVED, executor.mapActionToDecision(RiskActionRecord.ActionType.ALERT));
        assertEquals(RiskDecision.REJECTED, executor.mapActionToDecision(RiskActionRecord.ActionType.BLOCK));
        assertEquals(RiskDecision.PENDING_REVIEW, executor.mapActionToDecision(RiskActionRecord.ActionType.MANUAL_REVIEW));
        assertEquals(RiskDecision.APPROVED, executor.mapActionToDecision(RiskActionRecord.ActionType.CAPTURE));
        assertEquals(RiskDecision.APPROVED, executor.mapActionToDecision(null));
    }

    @Test
    void autoSelectAction_highScore_shouldReturnBlock() {
        Map<String, Integer> scores = Map.of("IP_SCORE", 90);
        RiskScoreResult highScore = RiskScoreResult.of(90, RiskDecision.REJECTED, scores, Map.of());

        assertEquals(RiskActionRecord.ActionType.BLOCK, executor.autoSelectAction(highScore));
    }

    @Test
    void autoSelectAction_mediumScore_shouldReturnManualReview() {
        Map<String, Integer> scores = Map.of("IP_SCORE", 65);
        RiskScoreResult mediumScore = RiskScoreResult.of(65, RiskDecision.PENDING_REVIEW, scores, Map.of());

        assertEquals(RiskActionRecord.ActionType.MANUAL_REVIEW, executor.autoSelectAction(mediumScore));
    }

    @Test
    void autoSelectAction_lowScore_shouldReturnAlert() {
        Map<String, Integer> scores = Map.of("IP_SCORE", 35);
        RiskScoreResult lowScore = RiskScoreResult.of(35, RiskDecision.APPROVED, scores, Map.of());

        assertEquals(RiskActionRecord.ActionType.ALERT, executor.autoSelectAction(lowScore));
    }

    @Test
    void autoSelectAction_veryLowScore_shouldReturnCapture() {
        Map<String, Integer> scores = Map.of("IP_SCORE", 10);
        RiskScoreResult veryLowScore = RiskScoreResult.of(10, RiskDecision.APPROVED, scores, Map.of());

        assertEquals(RiskActionRecord.ActionType.CAPTURE, executor.autoSelectAction(veryLowScore));
    }

    @Test
    void autoSelectAction_nullScoreResult_shouldReturnCapture() {
        assertEquals(RiskActionRecord.ActionType.CAPTURE, executor.autoSelectAction(null));
    }

    @Test
    void executeAction_manualReview_recordShouldBePending() {
        when(recordRepository.save(any(RiskActionRecord.class))).thenAnswer(invocation -> {
            RiskActionRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        executor.executeAction(RiskActionRecord.ActionType.MANUAL_REVIEW, scoreResult, transaction);

        verify(recordRepository).save(argThat(record ->
                record.getActionStatus() == RiskActionRecord.ActionStatus.PENDING));
    }

    @Test
    void executeAction_block_recordShouldBeExecuted() {
        when(recordRepository.save(any(RiskActionRecord.class))).thenAnswer(invocation -> {
            RiskActionRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        executor.executeAction(RiskActionRecord.ActionType.BLOCK, scoreResult, transaction);

        verify(recordRepository).save(argThat(record ->
                record.getActionStatus() == RiskActionRecord.ActionStatus.EXECUTED));
    }
}
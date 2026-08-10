package org.nexus.compliance.reputation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DefaultReputationService} 单元测试。
 */
class DefaultReputationServiceTest {

    private DefaultReputationService service;

    @BeforeEach
    void setUp() {
        service = new DefaultReputationService();
    }

    @Test
    void getScore_unknownAddress_shouldReturnInitial() {
        ReputationScore score = service.getScore("ADDR_NEW");

        assertEquals(60, score.getScore());
        assertEquals(ReputationScore.Grade.B, score.getGrade());
    }

    @Test
    void updateScore_positiveEvent_shouldIncrease() {
        service.updateScore("ADDR_A", ReputationEvent.EventType.PAYMENT_COMPLETED);

        ReputationScore score = service.getScore("ADDR_A");
        assertEquals(61, score.getScore());
    }

    @Test
    void updateScore_negativeEvent_shouldDecrease() {
        service.updateScore("ADDR_A", ReputationEvent.EventType.RISK_BLOCKED);

        ReputationScore score = service.getScore("ADDR_A");
        assertEquals(40, score.getScore());
        assertEquals(ReputationScore.Grade.C, score.getGrade());
    }

    @Test
    void updateScore_amlHighRisk_shouldDropToGradeD() {
        service.updateScore("ADDR_A", ReputationEvent.EventType.AML_HIGH_RISK);

        ReputationScore score = service.getScore("ADDR_A");
        assertEquals(20, score.getScore());
        assertEquals(ReputationScore.Grade.D, score.getGrade());
    }

    @Test
    void updateScore_typedEvent_shouldApply() {
        ReputationEvent event = new ReputationEvent(
                ReputationEvent.EventType.KYC_UPGRADED, "upgraded to ENHANCED");

        ReputationScore score = service.updateScore("ADDR_A", event);

        assertEquals(70, score.getScore());
        assertFalse(score.getHistoryEvents().isEmpty());
    }

    @Test
    void updateScore_scoreClampedToRange() {
        for (int i = 0; i < 20; i++) {
            service.updateScore("ADDR_A", ReputationEvent.EventType.AML_HIGH_RISK);
        }
        assertEquals(0, service.getScore("ADDR_A").getScore());

        for (int i = 0; i < 30; i++) {
            service.updateScore("ADDR_B", ReputationEvent.EventType.KYC_UPGRADED);
        }
        assertEquals(100, service.getScore("ADDR_B").getScore());
        assertEquals(ReputationScore.Grade.A, service.getScore("ADDR_B").getGrade());
    }

    @Test
    void getHistory_shouldReturnReverseChronological() {
        service.updateScore("ADDR_A", ReputationEvent.EventType.PAYMENT_COMPLETED);
        service.updateScore("ADDR_A", ReputationEvent.EventType.DISPUTE);

        List<String> history = service.getHistory("ADDR_A");

        assertEquals(2, history.size());
        // 最新事件（DISPUTE）在前
        assertEquals(true, history.get(0).contains("DISPUTE"));
    }

    @Test
    void updateScore_blankAddress_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateScore("", ReputationEvent.EventType.PAYMENT_COMPLETED));
    }
}

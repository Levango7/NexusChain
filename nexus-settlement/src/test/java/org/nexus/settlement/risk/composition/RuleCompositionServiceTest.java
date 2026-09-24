package org.nexus.settlement.risk.composition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.settlement.risk.RiskDecision;
import org.nexus.settlement.risk.RiskScoreResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RuleCompositionService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RuleCompositionServiceTest {

    @Mock
    private RuleCompositionRepository repository;

    @InjectMocks
    private RuleCompositionService service;

    private RuleComposition testComposition;

    @BeforeEach
    void setUp() {
        testComposition = new RuleComposition();
        testComposition.setId(1L);
        testComposition.setCompositionName("IP_REGION_AND");
        testComposition.setCompositionType(RuleComposition.CompositionType.AND);
        testComposition.setExpression("IP_SCORE & REGION_SCORE");
        testComposition.setPriority(10);
        testComposition.setAction(RuleComposition.ActionType.BLOCK);
        testComposition.setEnabled(true);
        testComposition.setScoreThreshold(70);
    }

    @Test
    void loadEnabledCompositions_shouldReturnEnabled() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(testComposition));

        List<RuleComposition> result = service.loadEnabledCompositions();

        assertEquals(1, result.size());
        assertEquals("IP_REGION_AND", result.get(0).getCompositionName());
    }

    @Test
    void evaluateCompositions_andLogic_allAboveThreshold_shouldTrigger() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(testComposition));

        Map<String, Integer> scores = Map.of("IP_SCORE", 80, "REGION_SCORE", 75);
        RiskScoreResult scoreResult = RiskScoreResult.of(77, RiskDecision.PENDING_REVIEW, scores, Map.of());

        RuleComposition.ActionType action = service.evaluateCompositions(scoreResult);

        assertEquals(RuleComposition.ActionType.BLOCK, action);
    }

    @Test
    void evaluateCompositions_andLogic_oneBelowThreshold_shouldNotTrigger() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(testComposition));

        Map<String, Integer> scores = Map.of("IP_SCORE", 80, "REGION_SCORE", 50);
        RiskScoreResult scoreResult = RiskScoreResult.of(65, RiskDecision.PENDING_REVIEW, scores, Map.of());

        RuleComposition.ActionType action = service.evaluateCompositions(scoreResult);

        assertNull(action);
    }

    @Test
    void evaluateCompositions_orLogic_anyAboveThreshold_shouldTrigger() {
        RuleComposition orComposition = new RuleComposition();
        orComposition.setCompositionType(RuleComposition.CompositionType.OR);
        orComposition.setExpression("IP_SCORE | REGION_SCORE");
        orComposition.setAction(RuleComposition.ActionType.MANUAL_REVIEW);
        orComposition.setEnabled(true);
        orComposition.setScoreThreshold(70);

        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(orComposition));

        Map<String, Integer> scores = Map.of("IP_SCORE", 80, "REGION_SCORE", 30);
        RiskScoreResult scoreResult = RiskScoreResult.of(55, RiskDecision.APPROVED, scores, Map.of());

        RuleComposition.ActionType action = service.evaluateCompositions(scoreResult);

        assertEquals(RuleComposition.ActionType.MANUAL_REVIEW, action);
    }

    @Test
    void evaluateCompositions_orLogic_noneAboveThreshold_shouldNotTrigger() {
        RuleComposition orComposition = new RuleComposition();
        orComposition.setCompositionType(RuleComposition.CompositionType.OR);
        orComposition.setExpression("IP_SCORE | REGION_SCORE");
        orComposition.setAction(RuleComposition.ActionType.MANUAL_REVIEW);
        orComposition.setEnabled(true);
        orComposition.setScoreThreshold(70);

        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(orComposition));

        Map<String, Integer> scores = Map.of("IP_SCORE", 30, "REGION_SCORE", 40);
        RiskScoreResult scoreResult = RiskScoreResult.of(35, RiskDecision.APPROVED, scores, Map.of());

        RuleComposition.ActionType action = service.evaluateCompositions(scoreResult);

        assertNull(action);
    }

    @Test
    void evaluateCompositions_nullScoreResult_shouldReturnNull() {
        assertNull(service.evaluateCompositions(null));
    }

    @Test
    void evaluateCompositions_noCompositions_shouldReturnNull() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of());

        Map<String, Integer> scores = Map.of("IP_SCORE", 80);
        RiskScoreResult scoreResult = RiskScoreResult.of(80, RiskDecision.REJECTED, scores, Map.of());

        assertNull(service.evaluateCompositions(scoreResult));
    }

    @Test
    void parseExpression_shouldExtractRuleIds() {
        List<String> result = service.parseExpression("IP_SCORE & REGION_SCORE");
        assertEquals(2, result.size());
        assertTrue(result.contains("IP_SCORE"));
        assertTrue(result.contains("REGION_SCORE"));
    }

    @Test
    void parseExpression_withPipeSeparator_shouldExtractRuleIds() {
        List<String> result = service.parseExpression("IP_SCORE | BLACKLIST_SCORE");
        assertEquals(2, result.size());
        assertTrue(result.contains("IP_SCORE"));
        assertTrue(result.contains("BLACKLIST_SCORE"));
    }

    @Test
    void parseExpression_withCommaSeparator_shouldExtractRuleIds() {
        List<String> result = service.parseExpression("IP_SCORE,REGION_SCORE");
        assertEquals(2, result.size());
        assertTrue(result.contains("IP_SCORE"));
        assertTrue(result.contains("REGION_SCORE"));
    }

    @Test
    void parseExpression_nullOrBlank_shouldReturnEmpty() {
        assertTrue(service.parseExpression(null).isEmpty());
        assertTrue(service.parseExpression("").isEmpty());
        assertTrue(service.parseExpression("   ").isEmpty());
    }

    @Test
    void mapActionToDecision_shouldMapCorrectly() {
        assertEquals(RiskDecision.APPROVED, service.mapActionToDecision(RuleComposition.ActionType.ALERT));
        assertEquals(RiskDecision.REJECTED, service.mapActionToDecision(RuleComposition.ActionType.BLOCK));
        assertEquals(RiskDecision.PENDING_REVIEW, service.mapActionToDecision(RuleComposition.ActionType.MANUAL_REVIEW));
        assertEquals(RiskDecision.APPROVED, service.mapActionToDecision(RuleComposition.ActionType.CAPTURE));
        assertEquals(RiskDecision.APPROVED, service.mapActionToDecision(null));
    }

    @Test
    void createComposition_shouldSaveNew() {
        when(repository.existsByCompositionName("NEW_COMP")).thenReturn(false);
        when(repository.save(any(RuleComposition.class))).thenReturn(testComposition);

        RuleComposition newComp = new RuleComposition();
        newComp.setCompositionName("NEW_COMP");

        RuleComposition result = service.createComposition(newComp);

        assertNotNull(result);
        verify(repository).save(newComp);
    }

    @Test
    void createComposition_duplicateName_shouldThrow() {
        when(repository.existsByCompositionName("IP_REGION_AND")).thenReturn(true);

        RuleComposition duplicate = new RuleComposition();
        duplicate.setCompositionName("IP_REGION_AND");

        assertThrows(IllegalArgumentException.class, () -> service.createComposition(duplicate));
    }

    @Test
    void updateComposition_shouldUpdateExisting() {
        RuleComposition existing = new RuleComposition();
        existing.setId(1L);
        existing.setCompositionName("OLD_NAME");

        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(RuleComposition.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RuleComposition update = new RuleComposition();
        update.setCompositionName("NEW_NAME");

        RuleComposition result = service.updateComposition(1L, update);

        assertEquals("NEW_NAME", result.getCompositionName());
    }

    @Test
    void deleteComposition_shouldDeleteById() {
        service.deleteComposition(1L);
        verify(repository).deleteById(1L);
    }
}
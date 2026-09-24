package org.nexus.settlement.risk.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RiskRuleConfigService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RiskRuleConfigServiceTest {

    @Mock
    private RiskRuleConfigRepository repository;

    @InjectMocks
    private RiskRuleConfigService service;

    private RiskRuleConfig testConfig;

    @BeforeEach
    void setUp() {
        testConfig = new RiskRuleConfig();
        testConfig.setId(1L);
        testConfig.setRuleName("IP_SCORE");
        testConfig.setRuleType(RiskRuleConfig.RuleType.SCORING);
        testConfig.setEnabled(true);
        testConfig.setPriority(10);
        testConfig.setThreshold(80);
        testConfig.setAction(RiskRuleConfig.ActionType.BLOCK);
    }

    @Test
    void loadEnabledRules_shouldReturnEnabledRules() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(testConfig));

        List<RiskRuleConfig> result = service.loadEnabledRules();

        assertEquals(1, result.size());
        assertEquals("IP_SCORE", result.get(0).getRuleName());
        verify(repository).findByEnabledTrueOrderByPriorityAsc();
    }

    @Test
    void loadEnabledRulesByType_shouldReturnFilteredRules() {
        when(repository.findByEnabledTrueAndRuleTypeOrderByPriorityAsc(RiskRuleConfig.RuleType.SCORING))
                .thenReturn(List.of(testConfig));

        List<RiskRuleConfig> result = service.loadEnabledRulesByType(RiskRuleConfig.RuleType.SCORING);

        assertEquals(1, result.size());
        verify(repository).findByEnabledTrueAndRuleTypeOrderByPriorityAsc(RiskRuleConfig.RuleType.SCORING);
    }

    @Test
    void findByRuleName_shouldReturnConfig() {
        when(repository.findByRuleName("IP_SCORE")).thenReturn(Optional.of(testConfig));

        Optional<RiskRuleConfig> result = service.findByRuleName("IP_SCORE");

        assertTrue(result.isPresent());
        assertEquals("IP_SCORE", result.get().getRuleName());
    }

    @Test
    void createRule_shouldSaveNewConfig() {
        when(repository.existsByRuleName("NEW_RULE")).thenReturn(false);
        when(repository.save(any(RiskRuleConfig.class))).thenReturn(testConfig);

        RiskRuleConfig newConfig = new RiskRuleConfig();
        newConfig.setRuleName("NEW_RULE");
        newConfig.setRuleType(RiskRuleConfig.RuleType.SCORING);

        RiskRuleConfig result = service.createRule(newConfig);

        assertNotNull(result);
        verify(repository).existsByRuleName("NEW_RULE");
        verify(repository).save(newConfig);
    }

    @Test
    void createRule_duplicateName_shouldThrow() {
        when(repository.existsByRuleName("IP_SCORE")).thenReturn(true);

        RiskRuleConfig duplicate = new RiskRuleConfig();
        duplicate.setRuleName("IP_SCORE");

        assertThrows(IllegalArgumentException.class, () -> service.createRule(duplicate));
    }

    @Test
    void updateRule_shouldUpdateExistingConfig() {
        RiskRuleConfig existing = new RiskRuleConfig();
        existing.setId(1L);
        existing.setRuleName("OLD_NAME");
        existing.setEnabled(true);

        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(RiskRuleConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RiskRuleConfig update = new RiskRuleConfig();
        update.setRuleName("NEW_NAME");
        update.setEnabled(false);

        RiskRuleConfig result = service.updateRule(1L, update);

        assertEquals("NEW_NAME", result.getRuleName());
        assertFalse(result.getEnabled());
        verify(repository).findById(1L);
        verify(repository).save(existing);
    }

    @Test
    void updateRule_notFound_shouldThrow() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.updateRule(999L, new RiskRuleConfig()));
    }

    @Test
    void deleteRule_shouldDeleteById() {
        service.deleteRule(1L);
        verify(repository).deleteById(1L);
    }

    @Test
    void enableRule_shouldSetEnabledTrue() {
        RiskRuleConfig config = new RiskRuleConfig();
        config.setRuleName("IP_SCORE");
        config.setEnabled(false);

        when(repository.findByRuleName("IP_SCORE")).thenReturn(Optional.of(config));
        when(repository.save(any(RiskRuleConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RiskRuleConfig result = service.enableRule("IP_SCORE");

        assertTrue(result.getEnabled());
        verify(repository).save(config);
    }

    @Test
    void disableRule_shouldSetEnabledFalse() {
        RiskRuleConfig config = new RiskRuleConfig();
        config.setRuleName("IP_SCORE");
        config.setEnabled(true);

        when(repository.findByRuleName("IP_SCORE")).thenReturn(Optional.of(config));
        when(repository.save(any(RiskRuleConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RiskRuleConfig result = service.disableRule("IP_SCORE");

        assertFalse(result.getEnabled());
        verify(repository).save(config);
    }

    @Test
    void enableRule_notFound_shouldThrow() {
        when(repository.findByRuleName("UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.enableRule("UNKNOWN"));
    }

    @Test
    void findAllRules_shouldReturnAll() {
        when(repository.findAll()).thenReturn(List.of(testConfig));

        List<RiskRuleConfig> result = service.findAllRules();

        assertEquals(1, result.size());
    }
}
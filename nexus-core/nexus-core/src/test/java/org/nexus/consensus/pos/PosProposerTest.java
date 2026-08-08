package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link PosProposer} 提案者选择器测试。
 */
public class PosProposerTest {

    private PosProposer proposer;
    private ValidatorRegistry registry;

    @BeforeEach
    public void setUp() {
        proposer = new PosProposer();
        registry = mock(ValidatorRegistry.class);
        injectField(proposer, "validatorRegistry", registry);
    }

    private static void injectField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testSelectProposerNoActiveValidators() {
        when(registry.getActiveValidators()).thenReturn(Collections.emptyList());
        assertNull(proposer.selectProposer(100L));
    }

    @Test
    public void testSelectProposerSingleValidator() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getActiveValidators()).thenReturn(Collections.singletonList(v));
        Validator selected = proposer.selectProposer(100L);
        assertNotNull(selected);
        assertEquals("addr1", selected.getAddress());
    }

    @Test
    public void testSelectProposerZeroTotalStake() {
        Validator v = new Validator("addr1", "pub1", BigDecimal.ZERO, 0.05, ValidatorStatus.ACTIVE);
        when(registry.getActiveValidators()).thenReturn(Collections.singletonList(v));
        assertNull(proposer.selectProposer(100L));
    }

    @Test
    public void testSelectProposerMultipleValidators() {
        Validator v1 = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        Validator v2 = new Validator("addr2", "pub2", new BigDecimal("2000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getActiveValidators()).thenReturn(Arrays.asList(v1, v2));
        Validator selected = proposer.selectProposer(100L);
        assertNotNull(selected);
        assertTrue(selected.getAddress().equals("addr1") || selected.getAddress().equals("addr2"));
    }

    @Test
    public void testSelectRoundRobinProposerNoActiveValidators() {
        when(registry.getActiveValidators()).thenReturn(Collections.emptyList());
        assertNull(proposer.selectRoundRobinProposer(100L));
    }

    @Test
    public void testSelectRoundRobinProposerSingleValidator() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getActiveValidators()).thenReturn(Collections.singletonList(v));
        Validator selected = proposer.selectRoundRobinProposer(100L);
        assertNotNull(selected);
        assertEquals("addr1", selected.getAddress());
    }

    @Test
    public void testSelectRoundRobinProposerMultipleValidators() {
        Validator v1 = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        Validator v2 = new Validator("addr2", "pub2", new BigDecimal("2000"), 0.05, ValidatorStatus.ACTIVE);
        Validator v3 = new Validator("addr3", "pub3", new BigDecimal("3000"), 0.05, ValidatorStatus.ACTIVE);
        List<Validator> validators = Arrays.asList(v1, v2, v3);
        when(registry.getActiveValidators()).thenReturn(validators);

        // height=0 -> index=0
        assertEquals("addr1", proposer.selectRoundRobinProposer(0L).getAddress());
        // height=1 -> index=1
        assertEquals("addr2", proposer.selectRoundRobinProposer(1L).getAddress());
        // height=2 -> index=2
        assertEquals("addr3", proposer.selectRoundRobinProposer(2L).getAddress());
        // height=3 -> index=0
        assertEquals("addr1", proposer.selectRoundRobinProposer(3L).getAddress());
        // height=-1 -> Math.abs(-1) % 3 = 1
        assertEquals("addr2", proposer.selectRoundRobinProposer(-1L).getAddress());
    }
}
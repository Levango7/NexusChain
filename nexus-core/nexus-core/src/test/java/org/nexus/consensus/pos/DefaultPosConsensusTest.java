package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultPosConsensus} 默认 PoS 共识实现测试。
 */
public class DefaultPosConsensusTest {

    private DefaultPosConsensus consensus;
    private PosConsensusEngine engine;

    @BeforeEach
    public void setUp() {
        consensus = new DefaultPosConsensus();
        engine = mock(PosConsensusEngine.class);
        injectField(consensus, "engine", engine);
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
    public void testPropose() {
        Block block = mock(Block.class);
        when(engine.propose()).thenReturn(block);
        assertSame(block, consensus.propose());
        verify(engine).propose();
    }

    @Test
    public void testValidate() {
        Block block = mock(Block.class);
        when(engine.validate(block)).thenReturn(true);
        assertTrue(consensus.validate(block));
        verify(engine).validate(block);
    }

    @Test
    public void testSlashValidator() {
        Validator v = new Validator("addr1", "pub1", BigDecimal.ZERO, 0.05, ValidatorStatus.ACTIVE);
        consensus.slash(v);
        verify(engine).slash(v);
    }

    @Test
    public void testSlashWithOffense() {
        Validator v = new Validator("addr1", "pub1", BigDecimal.ZERO, 0.05, ValidatorStatus.ACTIVE);
        when(engine.slash(v, SlashingService.Offense.DOUBLE_SIGN)).thenReturn(new BigDecimal("100"));
        BigDecimal result = consensus.slash(v, SlashingService.Offense.DOUBLE_SIGN);
        assertEquals(new BigDecimal("100"), result);
        verify(engine).slash(v, SlashingService.Offense.DOUBLE_SIGN);
    }

    @Test
    public void testGetEngine() {
        assertSame(engine, consensus.getEngine());
    }
}
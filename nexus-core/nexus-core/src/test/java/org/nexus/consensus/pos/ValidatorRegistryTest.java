package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ValidatorRegistry} 验证人注册中心测试。
 *
 * <p>覆盖注册、注销、查询、质押门槛校验、最大验证人数限制等核心逻辑。</p>
 */
public class ValidatorRegistryTest {

    private ValidatorRegistry registry;
    private static final BigDecimal MIN_STAKE = new BigDecimal("1000");
    private static final int MAX_VALIDATORS = 3;

    @BeforeEach
    public void setUp() {
        registry = new ValidatorRegistry(MIN_STAKE, MAX_VALIDATORS);
    }

    @Test
    public void testRegisterSuccess() {
        boolean result = registry.register("addr1", "pub1", new BigDecimal("1500"), 0.05);
        assertTrue(result);
        assertEquals(1, registry.getValidatorCount());
        Validator v = registry.getValidator("addr1");
        assertNotNull(v);
        assertEquals("addr1", v.getAddress());
        assertEquals(ValidatorStatus.ACTIVE, v.getStatus());
    }

    @Test
    public void testRegisterEmptyAddress() {
        assertFalse(registry.register("", "pub1", MIN_STAKE, 0.05));
        assertFalse(registry.register(null, "pub1", MIN_STAKE, 0.05));
        assertEquals(0, registry.getValidatorCount());
    }

    @Test
    public void testRegisterDuplicate() {
        assertTrue(registry.register("addr1", "pub1", MIN_STAKE, 0.05));
        assertFalse(registry.register("addr1", "pub2", MIN_STAKE, 0.05));
        assertEquals(1, registry.getValidatorCount());
    }

    @Test
    public void testRegisterExceedMax() {
        assertTrue(registry.register("addr1", "pub1", MIN_STAKE, 0.05));
        assertTrue(registry.register("addr2", "pub2", MIN_STAKE, 0.05));
        assertTrue(registry.register("addr3", "pub3", MIN_STAKE, 0.05));
        assertFalse(registry.register("addr4", "pub4", MIN_STAKE, 0.05));
        assertEquals(MAX_VALIDATORS, registry.getValidatorCount());
    }

    @Test
    public void testRegisterStakeBelowMinimum() {
        assertFalse(registry.register("addr1", "pub1", new BigDecimal("500"), 0.05));
        assertFalse(registry.register("addr2", "pub2", null, 0.05));
        assertEquals(0, registry.getValidatorCount());
    }

    @Test
    public void testRegisterStakeAtMinimum() {
        assertTrue(registry.register("addr1", "pub1", MIN_STAKE, 0.05));
    }

    @Test
    public void testUnregisterSuccess() {
        registry.register("addr1", "pub1", MIN_STAKE, 0.05);
        assertTrue(registry.unregister("addr1"));
        Validator v = registry.getValidator("addr1");
        assertNotNull(v);
        assertEquals(ValidatorStatus.INACTIVE, v.getStatus());
    }

    @Test
    public void testUnregisterNonExistent() {
        assertFalse(registry.unregister("nonexistent"));
    }

    @Test
    public void testGetValidatorNonExistent() {
        assertNull(registry.getValidator("nonexistent"));
    }

    @Test
    public void testGetActiveValidators() {
        registry.register("addr1", "pub1", MIN_STAKE, 0.05);
        registry.register("addr2", "pub2", MIN_STAKE, 0.05);
        registry.unregister("addr1");

        List<Validator> active = registry.getActiveValidators();
        assertEquals(1, active.size());
        assertEquals("addr2", active.get(0).getAddress());
    }

    @Test
    public void testGetAllValidators() {
        registry.register("addr1", "pub1", MIN_STAKE, 0.05);
        registry.register("addr2", "pub2", MIN_STAKE, 0.05);
        registry.unregister("addr1");

        List<Validator> all = registry.getAllValidators();
        assertEquals(2, all.size());
    }

    @Test
    public void testGetters() {
        assertEquals(MIN_STAKE, registry.getMinStakeAmount());
        assertEquals(MAX_VALIDATORS, registry.getMaxValidators());
        assertEquals(0, registry.getValidatorCount());
    }

    @Test
    public void testDefaultConstructor() {
        ValidatorRegistry def = new ValidatorRegistry();
        assertEquals(new BigDecimal("1000"), def.getMinStakeAmount());
        assertEquals(100, def.getMaxValidators());
    }

    @Test
    public void testLoadSnapshotWithoutPersister() {
        // persister 为 null 时 loadSnapshot 应直接返回不报错
        // 通过反射调用 @PostConstruct 方法
        try {
            java.lang.reflect.Method m = ValidatorRegistry.class.getDeclaredMethod("loadSnapshot");
            m.setAccessible(true);
            m.invoke(registry);
        } catch (Exception e) {
            fail("loadSnapshot should not throw: " + e.getMessage());
        }
    }

    @Test
    public void testSaveSnapshotWithoutPersister() {
        try {
            java.lang.reflect.Method m = ValidatorRegistry.class.getDeclaredMethod("saveSnapshot");
            m.setAccessible(true);
            m.invoke(registry);
        } catch (Exception e) {
            fail("saveSnapshot should not throw: " + e.getMessage());
        }
    }
}
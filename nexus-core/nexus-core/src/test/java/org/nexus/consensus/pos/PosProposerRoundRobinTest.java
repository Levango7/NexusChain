package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN-007 单 proposer 协调：round-robin 确定性测试。
 *
 * <p>验证：即使两节点以不同顺序注册相同验证人集合，
 * 同一高度必须选出同一个 proposer（全网确定性）——消除竞争出块。</p>
 */
class PosProposerRoundRobinTest {

    private ValidatorRegistry registry;
    private PosProposer proposer;

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        proposer = new PosProposer();
        java.lang.reflect.Field f;
        try {
            f = PosProposer.class.getDeclaredField("validatorRegistry");
            f.setAccessible(true);
            f.set(proposer, registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void registerActive(String addr) {
        registry.register(addr, "pub-" + addr, new BigDecimal("300"), 0.1);
        Validator v = registry.getValidator(addr);
        v.setStatus(ValidatorStatus.ACTIVE);
    }

    @Test
    void differentRegistrationOrderYieldsSameProposer() {
        String[] addrs = {"node-B", "node-C", "node-A"};

        // 节点 1：按 B,C,A 顺序注册
        for (String a : addrs) registerActive(a);
        Validator p1_height5 = proposer.selectRoundRobinProposer(5);
        Validator p1_height6 = proposer.selectRoundRobinProposer(6);

        // 节点 2：按 A,C,B 顺序注册（不同顺序）
        ValidatorRegistry registry2 = new ValidatorRegistry(new BigDecimal("100"), 100);
        for (String a : new String[]{"node-A", "node-C", "node-B"}) {
            registry2.register(a, "pub-" + a, new BigDecimal("300"), 0.1);
            registry2.getValidator(a).setStatus(ValidatorStatus.ACTIVE);
        }
        PosProposer proposer2 = new PosProposer();
        try {
            java.lang.reflect.Field f = PosProposer.class.getDeclaredField("validatorRegistry");
            f.setAccessible(true);
            f.set(proposer2, registry2);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Validator p2_height5 = proposer2.selectRoundRobinProposer(5);
        Validator p2_height6 = proposer2.selectRoundRobinProposer(6);

        // 同一高度，不同注册顺序 → 同一 proposer（按地址排序确定性）
        assertEquals(p1_height5.getAddress(), p2_height5.getAddress(),
                "高度 5 两节点应选同一 proposer（地址排序确定性）");
        assertEquals(p1_height6.getAddress(), p2_height6.getAddress(),
                "高度 6 两节点应选同一 proposer");
        assertNotEquals(p1_height5.getAddress(), p1_height6.getAddress(),
                "不同高度应轮换不同 proposer");
    }

    @Test
    void roundRobinCyclesThroughAllValidators() {
        for (String a : new String[]{"v1", "v2", "v3"}) registerActive(a);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (long h = 1; h <= 6; h++) {
            Validator v = proposer.selectRoundRobinProposer(h);
            seen.add(v.getAddress());
        }
        assertEquals(3, seen.size(), "6 高度应轮换覆盖全部 3 验证人");
    }

    @Test
    void noActiveValidatorsReturnsNull() {
        assertNull(proposer.selectRoundRobinProposer(1));
    }
}

package org.nexus.contract.engine;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContractContext 最终性查询轴测试（ADR-030 M5）。
 */
class ContractContextFinalityTest {

    @Test
    void isFinalizedFailClosedWhenOracleAbsent() {
        ContractContext ctx = new ContractContext("0xabc", "0xdef",
                BigInteger.valueOf(100), System.currentTimeMillis(), 21000);
        // 未注入 FinalityOracle 时必须 fail-closed：绝不把"无法判定"当作"已最终化"
        assertFalse(ctx.isFinalized(100));
    }

    @Test
    void isFinalizedDelegatesToOracle() {
        ContractContext ctx = new ContractContext("0xabc", "0xdef",
                BigInteger.valueOf(100), System.currentTimeMillis(), 21000);
        ctx.setFinalityOracle(height -> height >= 96);  // 模拟：96 高度以上已最终化
        assertFalse(ctx.isFinalized(64));
        assertTrue(ctx.isFinalized(96));
        assertTrue(ctx.isFinalized(128));
    }

    @Test
    void oracleWorksWithLambdaAdapters() {
        // 模拟 FinalityGadget.isFinalized 作为 oracle 的接入形态
        java.util.Set<Long> finalized = new java.util.HashSet<>();
        finalized.add(32L); finalized.add(64L);
        ContractContext ctx = new ContractContext();
        ctx.setFinalityOracle(finalized::contains);
        assertTrue(ctx.isFinalized(32));
        assertFalse(ctx.isFinalized(33));
    }
}

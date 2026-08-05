package org.nexus.contract.engine;

import org.junit.Test;
import org.nexus.core.contract.ChicoryWasmEngine;
import org.nexus.core.contract.GasMeter;
import org.nexus.core.contract.WasmEngine;
import org.nexus.core.contract.WasmInstance;

import static org.junit.Assert.*;

/**
 * ChicoryWasmEngine 单元测试：验证手工构造的最小 WASM 模块可被解析与执行。
 *
 * <p>测试用 WASM 模块为一个 {@code add(i64, i64) -> i64} 导出函数，
 * 字节码手工组装（magic + type/function/export/code 四个 section）。</p>
 */
public class ChicoryWasmEngineTest {

    /**
     * 最小 WASM 模块：导出 add(i64,i64)->i64，函数体为 local.get 0; local.get 1; i64.add。
     */
    private static final byte[] ADD_MODULE = new byte[] {
            // magic + version
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
            // type section: (i64,i64) -> i64
            0x01, 0x07, 0x01, 0x60, 0x02, 0x7E, 0x7E, 0x01, 0x7E,
            // function section: func 0 uses type 0
            0x03, 0x02, 0x01, 0x00,
            // export section: export "add" as func 0
            0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00,
            // code section: local.get 0; local.get 1; i64.add; end
            0x0A, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x7C, 0x0B
    };

    private final WasmEngine engine = new ChicoryWasmEngine();

    @Test
    public void testValidateAcceptsWellFormedModule() {
        assertTrue("合法 WASM 模块应通过校验", engine.validate(ADD_MODULE));
    }

    @Test
    public void testValidateRejectsGarbage() {
        assertFalse("非法字节码应校验失败", engine.validate(new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07}));
        assertFalse("null 应校验失败", engine.validate(null));
        assertFalse("过短字节码应校验失败", engine.validate(new byte[] {0x00}));
    }

    @Test
    public void testInstantiateAndCallAdd() {
        GasMeter meter = new GasMeter(GasMeter.DEFAULT_GAS_CAP);
        WasmInstance instance = engine.instantiate(ADD_MODULE, meter);

        // 参数：两个 i64（小端 8 字节）：3 和 4
        byte[] args = new byte[16];
        args[0] = 3;
        args[8] = 4;
        byte[] result = instance.call("add", args);

        assertEquals("结果应为 8 字节", 8, result.length);
        long sum = 0;
        for (int i = 7; i >= 0; i--) {
            sum = (sum << 8) | (result[i] & 0xff);
        }
        assertEquals("3 + 4 = 7", 7L, sum);
        assertTrue("执行应消耗 gas", instance.getGasUsed() > 0);
    }

    @Test(expected = org.nexus.core.contract.WasmExecutionException.class)
    public void testCallMissingExportThrows() {
        GasMeter meter = new GasMeter(GasMeter.DEFAULT_GAS_CAP);
        WasmInstance instance = engine.instantiate(ADD_MODULE, meter);
        instance.call("nonexistent", new byte[0]);
    }

    @Test(expected = org.nexus.core.contract.WasmExecutionException.class)
    public void testInstantiateEmptyCodeThrows() {
        engine.instantiate(new byte[0], new GasMeter());
    }
}

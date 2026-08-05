package org.nexus.core.contract;

import org.junit.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;

/**
 * EvmInterpreter 单元测试：验证内嵌 EVM 子集解释器的算术、栈与存储操作。
 */
public class EvmInterpreterTest {

    @Test
    public void testAddAndReturn() {
        // PUSH1 3; PUSH1 4; ADD; RETURN  → 栈顶 7 作为返回值
        byte[] code = new byte[] {0x60, 0x03, 0x60, 0x04, 0x01, (byte) 0xf3};
        GasMeter meter = new GasMeter(GasMeter.DEFAULT_GAS_CAP);
        EvmInterpreter interp = new EvmInterpreter(code, meter, new HashMap<>());
        interp.run();

        assertFalse("不应 revert", interp.isReverted());
        assertEquals("3 + 4 = 7", BigInteger.valueOf(7), interp.getReturnValue());
        assertTrue("应消耗 gas", interp.getGasUsed() > 0);
    }

    @Test
    public void testStorageStoreAndLoad() {
        // PUSH1 42; PUSH1 0; SSTORE   (slot0 = 42)
        // PUSH1 0; SLOAD; RETURN      (读回 slot0)
        byte[] code = new byte[] {
                0x60, 0x2A, 0x60, 0x00, 0x55,
                0x60, 0x00, 0x54, (byte) 0xf3
        };
        GasMeter meter = new GasMeter(GasMeter.DEFAULT_GAS_CAP);
        java.util.Map<BigInteger, byte[]> storage = new HashMap<>();
        EvmInterpreter interp = new EvmInterpreter(code, meter, storage);
        interp.run();

        assertEquals("slot0 应为 42", BigInteger.valueOf(42), interp.getReturnValue());
        assertNotNull("存储应有 slot0", storage.get(BigInteger.ZERO));
    }

    @Test
    public void testPushArgsFirstArgOnTop() {
        // 仅 RETURN：返回压入的栈顶
        byte[] code = new byte[] {(byte) 0xf3};
        GasMeter meter = new GasMeter(GasMeter.DEFAULT_GAS_CAP);
        EvmInterpreter interp = new EvmInterpreter(code, meter, new HashMap<>());
        interp.pushArgs(List.of(99L, 5L)); // 首参 99 应在栈顶
        interp.run();

        assertEquals("首参 99 应位于栈顶并作为返回值", BigInteger.valueOf(99), interp.getReturnValue());
    }

    @Test
    public void testRevert() {
        // PUSH1 1; REVERT
        byte[] code = new byte[] {0x60, 0x01, (byte) 0xfd};
        GasMeter meter = new GasMeter(GasMeter.DEFAULT_GAS_CAP);
        EvmInterpreter interp = new EvmInterpreter(code, meter, new HashMap<>());
        interp.run();

        assertTrue("应标记 revert", interp.isReverted());
    }

    @Test
    public void testJumpAndJumpDest() {
        // PUSH1 4; JUMP; (invalid region) ; JUMPDEST@4; PUSH1 7; RETURN
        byte[] code = new byte[] {
                0x60, 0x04, 0x56, 0x00,
                0x5b, 0x60, 0x07, (byte) 0xf3
        };
        GasMeter meter = new GasMeter(GasMeter.DEFAULT_GAS_CAP);
        EvmInterpreter interp = new EvmInterpreter(code, meter, new HashMap<>());
        interp.run();

        assertEquals("跳转后应执行 PUSH1 7", BigInteger.valueOf(7), interp.getReturnValue());
    }

    @Test(expected = WasmExecutionException.class)
    public void testUnsupportedOpcodeThrows() {
        // 0x20 = KECCAK256（未实现的子集外操作码）
        byte[] code = new byte[] {0x20};
        GasMeter meter = new GasMeter(GasMeter.DEFAULT_GAS_CAP);
        new EvmInterpreter(code, meter, new HashMap<>()).run();
    }
}

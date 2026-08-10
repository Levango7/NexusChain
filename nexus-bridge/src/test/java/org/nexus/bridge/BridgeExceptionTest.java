package org.nexus.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BridgeException} 单元测试：覆盖三种构造函数与错误码读取。
 */
class BridgeExceptionTest {

    @Test
    @DisplayName("单参数构造应使用默认错误码 BRIDGE_ERROR")
    void singleArgConstructor_defaultErrorCode() {
        BridgeException ex = new BridgeException("something failed");
        assertEquals("BRIDGE_ERROR", ex.getErrorCode());
        assertEquals("something failed", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("双参数构造应保留错误码与消息")
    void twoArgConstructor_preservesCodeAndMessage() {
        BridgeException ex = new BridgeException("INVALID_AMOUNT", "amount must be positive");
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
        assertEquals("amount must be positive", ex.getMessage());
    }

    @Test
    @DisplayName("三参数构造应保留错误码、消息与根因")
    void threeArgConstructor_preservesCause() {
        Throwable cause = new RuntimeException("root cause");
        BridgeException ex = new BridgeException("RPC_ERROR", "rpc failed", cause);
        assertEquals("RPC_ERROR", ex.getErrorCode());
        assertEquals("rpc failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("BridgeException 应是 RuntimeException 子类")
    void shouldBeRuntimeException() {
        BridgeException ex = new BridgeException("test");
        assertTrue(ex instanceof RuntimeException);
    }
}
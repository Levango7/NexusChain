package org.nexus.consortium.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApplicationException 单元测试。
 * 覆盖所有构造器、message/cause 传递。
 */
public class ApplicationExceptionTest {

    @Test
    public void testDefaultConstructor() {
        ApplicationException ex = new ApplicationException();
        assertNull(ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    public void testMessageConstructor() {
        ApplicationException ex = new ApplicationException("test error");
        assertEquals("test error", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    public void testMessageAndCauseConstructor() {
        Throwable cause = new RuntimeException("root cause");
        ApplicationException ex = new ApplicationException("test error", cause);
        assertEquals("test error", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    @Test
    public void testCauseConstructor() {
        Throwable cause = new RuntimeException("root cause");
        ApplicationException ex = new ApplicationException(cause);
        assertEquals(cause, ex.getCause());
        assertNotNull(ex.getMessage());
    }

    @Test
    public void testExceptionInheritance() {
        ApplicationException ex = new ApplicationException("test");
        assertTrue(ex instanceof Exception);
    }

    @Test
    public void testNullMessage() {
        ApplicationException ex = new ApplicationException((String) null);
        assertNull(ex.getMessage());
    }

    @Test
    public void testEmptyMessage() {
        ApplicationException ex = new ApplicationException("");
        assertEquals("", ex.getMessage());
    }

    @Test
    public void testNullCause() {
        ApplicationException ex = new ApplicationException("msg", null);
        assertEquals("msg", ex.getMessage());
        assertNull(ex.getCause());
    }
}
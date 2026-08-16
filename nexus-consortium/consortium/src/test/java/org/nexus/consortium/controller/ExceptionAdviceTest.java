package org.nexus.consortium.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExceptionAdvice 单元测试。
 * 覆盖异常处理行为。
 */
public class ExceptionAdviceTest {

    @Test
    public void testConstruction() {
        ExceptionAdvice advice = new ExceptionAdvice();
        assertNotNull(advice);
    }

    @Test
    public void testNotFoundExceptionHandling() {
        ExceptionAdvice advice = new ExceptionAdvice();
        Exception ex = new RuntimeException("test error");
        Object result = advice.notFoundException(ex);
        assertNotNull(result);
        assertTrue(result instanceof Response);
        Response resp = (Response) result;
        assertEquals(500, resp.getCode());
        assertEquals("test error", resp.getMessage());
    }

    @Test
    public void testNotFoundExceptionWithNullMessage() {
        ExceptionAdvice advice = new ExceptionAdvice();
        Exception ex = new RuntimeException();
        Object result = advice.notFoundException(ex);
        assertNotNull(result);
        assertTrue(result instanceof Response);
    }

    @Test
    public void testNotFoundExceptionWithApplicationException() {
        ExceptionAdvice advice = new ExceptionAdvice();
        Exception ex = new org.nexus.consortium.exception.ApplicationException("app error");
        Object result = advice.notFoundException(ex);
        assertNotNull(result);
        assertTrue(result instanceof Response);
        Response resp = (Response) result;
        assertEquals(500, resp.getCode());
        assertEquals("app error", resp.getMessage());
    }
}
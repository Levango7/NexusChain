package org.nexus.consortium.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommonAdvice 单元测试。
 * 覆盖 ResponseBodyAdvice 实现与 supports 方法。
 */
public class CommonAdviceTest {

    @Test
    public void testImplementsResponseBodyAdvice() {
        CommonAdvice advice = new CommonAdvice();
        assertNotNull(advice);
        assertTrue(advice instanceof org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice);
    }

    @Test
    public void testSupportsReturnsTrue() {
        CommonAdvice advice = new CommonAdvice();
        assertTrue(advice.supports(null, null));
    }

    @Test
    public void testBeforeBodyWriteWithByteArray() {
        CommonAdvice advice = new CommonAdvice();
        byte[] body = new byte[]{1, 2, 3};
        Object result = advice.beforeBodyWrite(
                body, null, MediaType.APPLICATION_JSON, null,
                null, null);
        assertSame(body, result);
    }

    @Test
    public void testBeforeBodyWriteWithString() {
        CommonAdvice advice = new CommonAdvice();
        String body = "test";
        Object result = advice.beforeBodyWrite(
                body, null, MediaType.APPLICATION_JSON, null,
                null, null);
        assertSame(body, result);
    }

    @Test
    public void testBeforeBodyWriteWithResponse() {
        CommonAdvice advice = new CommonAdvice();
        Response body = Response.newSuccessFul("data");
        Object result = advice.beforeBodyWrite(
                body, null, MediaType.APPLICATION_JSON, null,
                null, null);
        assertSame(body, result);
    }

    @Test
    public void testBeforeBodyWriteWithInteger() {
        CommonAdvice advice = new CommonAdvice();
        Object body = 42;
        Object result = advice.beforeBodyWrite(
                body, null, MediaType.APPLICATION_JSON, null,
                null, null);
        assertNotNull(result);
        assertTrue(result instanceof Response);
        assertEquals(200, ((Response) result).getCode());
    }
}

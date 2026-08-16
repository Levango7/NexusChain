package org.nexus.consortium.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Response 单元测试。
 * 覆盖工厂方法、Code 枚举、getter 行为。
 */
public class ResponseTest {

    @Test
    public void testNewSuccessFul() {
        Response resp = Response.newSuccessFul("data");
        assertEquals(200, resp.getCode());
        assertEquals("success", resp.getMessage());
        assertEquals("data", resp.getData());
    }

    @Test
    public void testNewSuccessFulWithNull() {
        Response resp = Response.newSuccessFul(null);
        assertEquals(200, resp.getCode());
        assertEquals("success", resp.getMessage());
        assertNull(resp.getData());
    }

    @Test
    public void testNewSuccessFulWithNumber() {
        Response resp = Response.newSuccessFul(42);
        assertEquals(200, resp.getCode());
        assertEquals(42, resp.getData());
    }

    @Test
    public void testNewFailed() {
        Response resp = Response.newFailed(Response.Code.INTERNAL_ERROR);
        assertEquals(500, resp.getCode());
        assertEquals("internal error", resp.getMessage());
        assertEquals("", resp.getData());
    }

    @Test
    public void testNewFailedWithReason() {
        Response resp = Response.newFailed(Response.Code.INTERNAL_ERROR, "db down");
        assertEquals(500, resp.getCode());
        assertEquals("db down", resp.getMessage());
        assertEquals("", resp.getData());
    }

    @Test
    public void testCodeSuccessEnum() {
        assertEquals(200, Response.Code.SUCCESS.code);
        assertEquals("success", Response.Code.SUCCESS.message);
    }

    @Test
    public void testCodeInternalErrorEnum() {
        assertEquals(500, Response.Code.INTERNAL_ERROR.code);
        assertEquals("internal error", Response.Code.INTERNAL_ERROR.message);
    }

    @Test
    public void testNewSuccessFulWithObject() {
        Object obj = new Object();
        Response resp = Response.newSuccessFul(obj);
        assertEquals(200, resp.getCode());
        assertEquals(obj, resp.getData());
    }

    @Test
    public void testNewFailedWithNullReason() {
        Response resp = Response.newFailed(Response.Code.INTERNAL_ERROR, null);
        assertEquals(500, resp.getCode());
        assertNull(resp.getMessage());
    }

    @Test
    public void testNewFailedWithEmptyReason() {
        Response resp = Response.newFailed(Response.Code.INTERNAL_ERROR, "");
        assertEquals(500, resp.getCode());
        assertEquals("", resp.getMessage());
    }
}
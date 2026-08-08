package org.nexus.ApiResult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link APIResult} 单元测试。
 */
class APIResultTest {

    @Test
    void constantsAreSet() {
        assertEquals(5000, APIResult.FAIL);
        assertEquals(2000, APIResult.SUCCESS);
    }

    @Test
    void newSuccessHasSuccessCodeAndData() {
        APIResult<String> r = APIResult.newSuccess("hello");
        assertEquals(2000, r.getCode());
        assertEquals("hello", r.getData());
        assertNull(r.getMessage());
    }

    @Test
    void newFailedHasFailCodeAndMessage() {
        APIResult<String> r = APIResult.newFailed("error msg");
        assertEquals(5000, r.getCode());
        assertEquals("error msg", r.getMessage());
        assertNull(r.getData());
    }

    @Test
    void newFailResultWithCodeAndMessage() {
        APIResult<String> r = APIResult.newFailResult(4004, "not found");
        assertEquals(4004, r.getCode());
        assertEquals("not found", r.getMessage());
        assertNull(r.getData());
    }

    @Test
    void newFailResultWithCodeMessageAndData() {
        APIResult<Integer> r = APIResult.newFailResult(500, "internal", 42);
        assertEquals(500, r.getCode());
        assertEquals("internal", r.getMessage());
        assertEquals(42, r.getData());
    }

    @Test
    void setDataAndGet() {
        APIResult<String> r = new APIResult<>();
        r.setData("x");
        assertEquals("x", r.getData());
    }

    @Test
    void setMessageAndGet() {
        APIResult<String> r = new APIResult<>();
        r.setMessage("msg");
        assertEquals("msg", r.getMessage());
    }

    @Test
    void setCodeAndGet() {
        APIResult<String> r = new APIResult<>();
        r.setCode(123);
        assertEquals(123, r.getCode());
    }
}
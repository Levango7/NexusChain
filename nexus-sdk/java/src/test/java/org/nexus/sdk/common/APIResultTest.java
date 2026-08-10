package org.nexus.sdk.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * APIResult 与 ResultSupport 单元测试。
 */
class APIResultTest {

    @Test
    void apiResult_defaultConstructor_shouldHaveZeroCodeAndNullFields() {
        APIResult<String> result = new APIResult<>();

        assertEquals(0, result.getStatusCode());
        assertNull(result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void apiResult_settersAndGetters_shouldRoundTrip() {
        APIResult<String> result = new APIResult<>();
        result.setStatusCode(200);
        result.setMessage("OK");
        result.setData("payload");

        assertEquals(200, result.getStatusCode());
        assertEquals("OK", result.getMessage());
        assertEquals("payload", result.getData());
    }

    @Test
    void newFailResult_withData_shouldSetAllFields() {
        APIResult<String> result = APIResult.newFailResult(500, "error", "data");

        assertEquals(500, result.getStatusCode());
        assertEquals("error", result.getMessage());
        assertEquals("data", result.getData());
    }

    @Test
    void newFailResult_withoutData_shouldSetCodeAndMessage() {
        APIResult<String> result = APIResult.newFailResult(404, "not found");

        assertEquals(404, result.getStatusCode());
        assertEquals("not found", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void newSuccessResult_shouldSetDataOnly() {
        APIResult<Integer> result = APIResult.newSuccessResult(42);

        assertEquals(0, result.getStatusCode());
        assertNull(result.getMessage());
        assertEquals(42, result.getData());
    }

    @Test
    void newSuccessResult_withNullData_shouldKeepNull() {
        APIResult<String> result = APIResult.newSuccessResult(null);

        assertNull(result.getData());
    }

    @Test
    void resultSupport_settersAndGetters_shouldRoundTrip() {
        ResultSupport rs = new ResultSupport();
        rs.setMessage("hello");
        rs.setStatusCode(201);

        assertEquals("hello", rs.getMessage());
        assertEquals(201, rs.getStatusCode());
    }

    @Test
    void resultSupport_defaultValues_shouldBeZeroAndNull() {
        ResultSupport rs = new ResultSupport();

        assertEquals(0, rs.getStatusCode());
        assertNull(rs.getMessage());
    }
}

package org.nexus.sdk.client.feign;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SigningResponses} 单元测试：2000（签名成功）与 2001（待人工审批）必须可区分。
 *
 * <p>背景：执行腿断链的根因是 2001 响应没有 {@code data} 字段，调用方一律用
 * {@code txHash(...) == null} 判定"签名失败"，导致大额转账在审批前就被标成 FAILED。
 * 本组用例把两种响应的提取语义钉死。</p>
 */
class SigningResponsesTest {

    private static Map<String, Object> response(Object statusCode, Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put(SigningResponses.KEY_STATUS_CODE, statusCode);
        if (data != null) {
            response.put(SigningResponses.KEY_DATA, data);
        }
        return response;
    }

    @Test
    void txHash_extractsHashFromSuccessfulResponse() {
        assertEquals("0xabc", SigningResponses.txHash(response(2000, "0xabc")));
    }

    @Test
    void statusCode_acceptsBothNumberAndStringForms() {
        assertEquals("0xabc", SigningResponses.txHash(response(2000L, "0xabc")));
        assertEquals("0xabc", SigningResponses.txHash(response("2000", "0xabc")));
    }

    @Test
    void txHash_isNullForNonOkStatusAndBlankData() {
        assertNull(SigningResponses.txHash(response(5000, "0xabc")));
        assertNull(SigningResponses.txHash(response(2000, "   ")));
        assertNull(SigningResponses.txHash(response(2000, null)));
        assertNull(SigningResponses.txHash(response("not-a-number", "0xabc")));
        assertNull(SigningResponses.txHash(response(null, "0xabc")));
        assertNull(SigningResponses.txHash(null));
    }

    @Test
    void approvalResponse_isRecognisedAndYieldsApprovalId() {
        Map<String, Object> response = response(2001, null);
        response.put("status", "PENDING");
        response.put("approvalId", "3f2b8c40-1d2e-4c55-9a11-7b9d0e2f4a66");

        assertTrue(SigningResponses.isApprovalRequired(response));
        assertEquals("3f2b8c40-1d2e-4c55-9a11-7b9d0e2f4a66", SigningResponses.approvalId(response));
        // 2001 没有 data：txHash 必然为 null，调用方不得把它当签名失败
        assertNull(SigningResponses.txHash(response));
    }

    @Test
    void approvalId_isNullWhenResponseIsNotApprovalRequired() {
        assertNull(SigningResponses.approvalId(response(2000, "0xabc")));
        assertNull(SigningResponses.approvalId(response(5000, "x")));
        assertNull(SigningResponses.approvalId(null));
        assertFalse(SigningResponses.isApprovalRequired(null));
    }

    @Test
    void approvalId_isNullWhenFieldMissingOrBlank() {
        assertNull(SigningResponses.approvalId(response(2001, null)));

        Map<String, Object> blank = response(2001, null);
        blank.put("approvalId", "  ");
        assertNull(SigningResponses.approvalId(blank));

        Map<String, Object> numberId = response(2001, null);
        numberId.put("approvalId", 42);
        assertEquals("42", SigningResponses.approvalId(numberId));
    }

    @Test
    void message_extractsOrReturnsNull() {
        Map<String, Object> response = response(5000, null);
        response.put("message", "审批未通过");
        assertEquals("审批未通过", SigningResponses.message(response));
        assertNull(SigningResponses.message(response(5000, null)));
        assertNull(SigningResponses.message(null));
    }

    // --- classify：三态契约（待审批不得再被当成失败） ---

    @Test
    void classify_nullResponse_isFailed() {
        SigningResponses.Outcome outcome = SigningResponses.classify(null);
        assertTrue(outcome.isFailed());
        assertNull(outcome.txHash());
        assertNull(outcome.approvalId());
        assertNull(outcome.statusCode());
        assertNotNull(outcome.message());
    }

    @Test
    void classify_okWithHash_isExecuted() {
        SigningResponses.Outcome outcome = SigningResponses.classify(response(2000, "0xabc"));
        assertTrue(outcome.isExecuted());
        assertEquals("0xabc", outcome.txHash());
        assertNull(outcome.approvalId());
        assertEquals(2000, outcome.statusCode());
    }

    @Test
    void classify_approvalRequired_isAwaitingWithApprovalId() {
        Map<String, Object> response = response(2001, null);
        response.put("approvalId", "appr-1");

        SigningResponses.Outcome outcome = SigningResponses.classify(response);
        assertTrue(outcome.isAwaitingApproval());
        assertFalse(outcome.isFailed());
        assertEquals("appr-1", outcome.approvalId());
        assertNull(outcome.txHash());
        assertEquals(2001, outcome.statusCode());
    }

    @Test
    void classify_approvalRequiredWithoutId_isFailed() {
        SigningResponses.Outcome outcome = SigningResponses.classify(response(2001, null));
        assertTrue(outcome.isFailed());
        assertNull(outcome.approvalId());
        assertEquals(2001, outcome.statusCode());
    }

    @Test
    void classify_okWithoutHash_isFailed() {
        assertTrue(SigningResponses.classify(response(2000, null)).isFailed());
        assertTrue(SigningResponses.classify(response(2000, "   ")).isFailed());
    }

    @Test
    void classify_otherStatus_isFailedWithMessage() {
        Map<String, Object> response = response(5000, null);
        response.put("message", "签名服务内部错误");
        SigningResponses.Outcome outcome = SigningResponses.classify(response);
        assertTrue(outcome.isFailed());
        assertEquals("签名服务内部错误", outcome.message());
        assertEquals(5000, outcome.statusCode());
    }

    @Test
    void classify_acceptsStringStatusCodeAndNumericApprovalId() {
        Map<String, Object> response = response("2001", null);
        response.put("approvalId", 42);
        SigningResponses.Outcome outcome = SigningResponses.classify(response);
        assertTrue(outcome.isAwaitingApproval());
        assertEquals("42", outcome.approvalId());
    }

    @Test
    void classify_invalidStatusCode_isFailed() {
        SigningResponses.Outcome outcome = SigningResponses.classify(response("oops", "0xabc"));
        assertTrue(outcome.isFailed());
        assertNull(outcome.statusCode());
    }
}

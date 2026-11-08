package org.nexus.gateway.orchestration.connector;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败分类测试。
 *
 * <p>核心约束：分不清时一律 DECLINE（不熔断）。误判成 UNAVAILABLE 会掐掉
 * 活着的收款通道；误判成 DECLINE 只是退化回现状。</p>
 */
class ConnectorFailureClassifierTest {

    // ==================== 未知 → DECLINE（不熔断） ====================

    @Test
    void nullClassifiesAsDecline() {
        assertEquals(FailureKind.DECLINE, ConnectorFailureClassifier.classify(null));
        assertFalse(FailureKind.countsAsBreakerFailure(null));
    }

    @Test
    void unknownExceptionWithTimeoutTextStillDeclines() {
        // 文案不是结构化信号：即使消息里写着 timeout/503，也不得熔断。
        FailureKind kind = ConnectorFailureClassifier.classify(
                new IllegalStateException("503 Service Unavailable / read timed out"));
        assertEquals(FailureKind.DECLINE, kind);
        assertFalse(kind.isBreakerFailure());
    }

    @Test
    void unknownBusinessExceptionDeclines() {
        assertEquals(FailureKind.DECLINE,
                ConnectorFailureClassifier.classify(new RuntimeException("insufficient funds")));
    }

    @Test
    void unknownExceptionWithNonMatchingCauseDeclines() {
        assertEquals(FailureKind.DECLINE,
                ConnectorFailureClassifier.classify(
                        new RuntimeException("wrapped", new IllegalArgumentException("bad request"))));
    }

    // ==================== 结构化信号 → UNAVAILABLE ====================

    @Test
    void http5xxIsUnavailable() {
        assertEquals(FailureKind.UNAVAILABLE, ConnectorFailureClassifier.classify(
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)));
    }

    @Test
    void http429IsUnavailable() {
        assertEquals(FailureKind.UNAVAILABLE, ConnectorFailureClassifier.classify(
                new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)));
    }

    @Test
    void http408IsUnavailable() {
        assertEquals(FailureKind.UNAVAILABLE, ConnectorFailureClassifier.classify(
                new HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT)));
    }

    @Test
    void connectTimeoutExceptionIsUnavailable() {
        assertEquals(FailureKind.UNAVAILABLE, ConnectorFailureClassifier.classify(
                new ResourceAccessException("connect timed out", new SocketTimeoutException("connect timed out"))));
    }

    @Test
    void connectExceptionIsUnavailable() {
        assertEquals(FailureKind.UNAVAILABLE, ConnectorFailureClassifier.classify(
                new ResourceAccessException("connection refused", new ConnectException("connection refused"))));
    }

    @Test
    void ioExceptionIsUnavailable() {
        assertEquals(FailureKind.UNAVAILABLE,
                ConnectorFailureClassifier.classify(new IOException("broken pipe")));
    }

    @Test
    void wrappedIoExceptionIsUnavailable() {
        assertEquals(FailureKind.UNAVAILABLE,
                ConnectorFailureClassifier.classify(new RuntimeException("wrapper", new IOException("reset"))));
    }

    // ==================== 结构化信号 → DECLINE / CONFIG ====================

    @Test
    void http4xxBusinessErrorDeclines() {
        assertEquals(FailureKind.DECLINE, ConnectorFailureClassifier.classify(
                new HttpClientErrorException(HttpStatus.BAD_REQUEST)));
    }

    @Test
    void http401IsConfigNotUnavailable() {
        FailureKind kind = ConnectorFailureClassifier.classify(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        assertEquals(FailureKind.CONFIG, kind);
        assertFalse(kind.isBreakerFailure());
    }

    // ==================== HTTP 状态码直接分类 ====================

    @Test
    void classifyHttpStatusBoundaries() {
        assertEquals(FailureKind.DECLINE, ConnectorFailureClassifier.classifyHttpStatus(400));
        assertEquals(FailureKind.DECLINE, ConnectorFailureClassifier.classifyHttpStatus(404));
        assertEquals(FailureKind.UNAVAILABLE, ConnectorFailureClassifier.classifyHttpStatus(429));
        assertEquals(FailureKind.UNAVAILABLE, ConnectorFailureClassifier.classifyHttpStatus(500));
        assertEquals(FailureKind.UNAVAILABLE, ConnectorFailureClassifier.classifyHttpStatus(503));
        assertEquals(FailureKind.CONFIG, ConnectorFailureClassifier.classifyHttpStatus(401));
        assertEquals(FailureKind.CONFIG, ConnectorFailureClassifier.classifyHttpStatus(403));
    }

    @Test
    void onlyUnavailableCountsAsBreakerFailure() {
        assertTrue(FailureKind.countsAsBreakerFailure(FailureKind.UNAVAILABLE));
        assertFalse(FailureKind.countsAsBreakerFailure(FailureKind.DECLINE));
        assertFalse(FailureKind.countsAsBreakerFailure(FailureKind.CONFIG));
    }
}

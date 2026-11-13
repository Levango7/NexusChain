package org.nexus.gateway.orchestration.connector;

import org.springframework.web.client.HttpStatusCodeException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * 把 connector 抛出的异常映射成 {@link FailureKind}。
 *
 * <p>只使用结构化信号：异常类型 + HTTP 状态码。**不看异常消息文本**
 * （消息随 PSP 文案变化，匹配会静默失效）。</p>
 *
 * <p>无法识别的异常、{@code null}、以及没有结构化线索的包装异常一律返回
 * {@link FailureKind#DECLINE}，遵守 {@link FailureKind} 上的资金路径硬规则。</p>
 */
public final class ConnectorFailureClassifier {

    /** 防止异常 cause 链成环。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    private ConnectorFailureClassifier() {
    }

    /** 分类异常；未知一律 {@link FailureKind#DECLINE}。 */
    public static FailureKind classify(Throwable error) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cursor instanceof HttpStatusCodeException http) {
                return classifyHttpStatus(http.getStatusCode().value());
            }
            if (cursor instanceof SocketTimeoutException) {
                return FailureKind.UNAVAILABLE;
            }
            if (cursor instanceof ConnectException) {
                return FailureKind.UNAVAILABLE;
            }
            if (cursor instanceof UnknownHostException) {
                return FailureKind.UNAVAILABLE;
            }
            if (cursor instanceof SSLException) {
                return FailureKind.UNAVAILABLE;
            }
            if (cursor instanceof IOException) {
                return FailureKind.UNAVAILABLE;
            }
            cursor = cursor.getCause();
        }
        return FailureKind.DECLINE;
    }

    /**
     * 按 HTTP 状态码分类。408/425/429 与 5xx 视为基础设施不可用；
     * 401/403 视为配置/鉴权问题；其余 4xx（含 400 参数错误与业务拒绝）一律 DECLINE。
     */
    public static FailureKind classifyHttpStatus(int status) {
        if (status == 401 || status == 403) {
            return FailureKind.CONFIG;
        }
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return FailureKind.UNAVAILABLE;
        }
        return FailureKind.DECLINE;
    }
}

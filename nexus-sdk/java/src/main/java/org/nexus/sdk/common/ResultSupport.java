package org.nexus.sdk.common;

import java.io.Serializable;

/**
 * API 返回辅助基类。
 *
 * <p>原位于 {@code org.nexus.wallet.ApiResult.ResultSupport}（nexus-exchange-wallet），
 * 在 Phase 1 微服务化中迁移至 nexus-sdk 共享层（新包 {@code org.nexus.sdk.common}）。
 * 保留以兼容可能的外部引用；新代码建议直接使用 {@link APIResult}。</p>
 */
public class ResultSupport implements Serializable {
    private String message;
    private int StatusCode;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getStatusCode() {
        return StatusCode;
    }

    public void setStatusCode(int statusCode) {
        StatusCode = statusCode;
    }
}
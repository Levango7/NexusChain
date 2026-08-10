package org.nexus.sdk.common;

import java.io.Serializable;

/**
 * 跨服务共享的 API 返回包装 DTO。
 *
 * <p>原位于 {@code org.nexus.wallet.ApiResult.APIResult}（nexus-exchange-wallet），
 * 在 Phase 1 微服务化中迁移至 nexus-sdk 共享层（新包 {@code org.nexus.sdk.common}），
 * 供 nexus-signing-service / nexus-wallet-service / nexus-bridge / nexus-gateway
 * 等多个服务共同依赖，避免跨服务复制返回包装。</p>
 *
 * <p>迁移历史：原 exchange-wallet 包内的同名类已删除，所有引用已更新至本包路径。
 * 类名保留全大写 {@code APIResult} 以减少 import 改动量并保持向后兼容。
 * JSON 字段结构保持不变，仅 Java 包路径变更。</p>
 */
public class APIResult<T> implements Serializable {
    private int statusCode;
    private String message;
    protected T data;

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    /**
     * 接口调用失败,有错误字符串码和描述,有返回对象
     */
    public static <U> APIResult<U> newFailResult(int code, String message, U data) {
        APIResult<U> apiResult = new APIResult<U>();
        apiResult.setStatusCode(code);
        apiResult.setMessage(message);
        apiResult.setData(data);
        return apiResult;
    }

    /**
     * 接口调用失败,有错误字符串码和描述,没有返回对象
     */
    public static <U> APIResult<U> newFailResult(int code, String message) {
        APIResult<U> apiResult = new APIResult<U>();
        apiResult.setStatusCode(code);
        apiResult.setMessage(message);
        return apiResult;
    }

    public static <U> APIResult<U> newSuccessResult(U data) {
        APIResult<U> apiResult = new APIResult<U>();
        apiResult.setData(data);
        return apiResult;
    }
}
package org.nexus.wallet.ApiResult;

import java.io.Serializable;

/**
 * Self-contained API result wrapper (previously extended {@code com.company.ApiResult.ResultSupport}
 * from the deleted nexus-java-sdk module). Now standalone to eliminate the cross-module dependency.
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

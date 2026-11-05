/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.ApiResult;

public class APIResult<T> extends ResultSupport {
    public static int FAIL = 5000;
    public static int SUCCESS = 2000;

    /**
     * 支付扩展类型（0x10–0x1a）为保留类型，当前未启用。
     *
     * <p>读接口在未启用时统一返回此稳定错误码，便于调用方区分
     * 「未启用」与「实现故障」。裁决依据见
     * {@code docs/decision-payment-extension-onchain.md} §9。</p>
     */
    public static final int PAYMENT_EXTENSION_NOT_ENABLED = 5010;

    /** 支付扩展读接口统一的「未启用」消息。 */
    public static final String PAYMENT_EXTENSION_NOT_ENABLED_MESSAGE =
            "Payment extension (0x10-0x1a) is reserved but not enabled";

    protected T data;

    /**
     * 支付扩展读接口统一的「未启用」响应。
     *
     * @param <U> 数据类型
     * @return code 固定为 {@link #PAYMENT_EXTENSION_NOT_ENABLED} 的失败响应
     */
    public static <U> APIResult<U> paymentExtensionNotEnabled() {
        return newFailResult(PAYMENT_EXTENSION_NOT_ENABLED, PAYMENT_EXTENSION_NOT_ENABLED_MESSAGE);
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    /**
     * 接口调用失败,有错误字符串码和描述,有返回对象
     * @param code
     * @param message
     * @param data
     * @param <U>
     * @return
     */
    public static <U> APIResult<U> newFailResult(int code, String message, U data) {
        APIResult<U> apiResult = new APIResult<U>();
        apiResult.setCode(code);
        apiResult.setMessage(message);
        apiResult.setData(data);
        return apiResult;
    }

    /**
     * 接口调用失败,有错误字符串码和描述,没有返回对象
     * @param code
     * @param message
     * @param <U>
     * @return
     */
    public static <U> APIResult<U> newFailResult(int code, String message) {
        APIResult<U> apiResult = new APIResult<U>();
        apiResult.setCode(code);
        apiResult.setMessage(message);
        return apiResult;
    }

    public static <U> APIResult<U> newFailed(String message) {
        APIResult<U> apiResult = new APIResult<U>();
        apiResult.setCode(FAIL);
        apiResult.setMessage(message);
        return apiResult;
    }

    public static <U> APIResult<U> newSuccess(U data){
        APIResult<U> apiResult = new APIResult<U>();
        apiResult.setData(data);
        apiResult.setCode(SUCCESS);
        return apiResult;
    }
}
package org.nexus.sdk.client.feign;

import java.util.Map;

/**
 * 签名服务响应提取工具（质量审查 2026-09-10，Feign 契约修正配套）。
 *
 * <p>{@code SigningServiceFeignClient.signTransfer} 修正为返回
 * {@code Map<String, Object>}（签名服务 TxController 的真实响应形状：
 * {@code {"statusCode":2000,"data":"<txhash>","message":...}}）。
 * 消费方（gateway / wallet-service）经本类提取业务字段，统一校验
 * statusCode 与判空逻辑，避免各调用点散落重复解析。</p>
 *
 * <p>降级/fail-closed 语义：响应为 null（fallback 触发）、statusCode 非
 * 2000、或 data 缺失/空白时，{@link #txHash} 返回 {@code null}——与调用方
 * 既有的"null = 签名失败，标记 FAILED"约定完全一致。</p>
 */
public final class SigningResponses {

    /** 签名服务成功状态码（与 TxController 的 statusCode 常量对齐）。 */
    public static final int STATUS_OK = 2000;

    /** "data" 字段名（交易哈希所在键）。 */
    public static final String KEY_STATUS_CODE = "statusCode";
    public static final String KEY_DATA = "data";

    private SigningResponses() {
    }

    /**
     * 从 signTransfer 响应中提取交易哈希。
     *
     * @param response Feign 返回的响应 Map（可能为 null——fallback 降级）
     * @return 交易哈希；响应为 null / statusCode 非 2000 / data 缺失或空白
     *         时返回 null（调用方按签名失败处理）
     */
    public static String txHash(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object status = response.get(KEY_STATUS_CODE);
        // Jackson 反序列化数字默认为 Integer/Long；兼容 Number 与字符串两种形态
        int code;
        if (status instanceof Number n) {
            code = n.intValue();
        } else if (status != null) {
            try {
                code = Integer.parseInt(status.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        if (code != STATUS_OK) {
            return null;
        }
        Object data = response.get(KEY_DATA);
        if (data == null) {
            return null;
        }
        String txHash = data.toString();
        return txHash.isBlank() ? null : txHash;
    }

    /**
     * 提取签名服务响应中的 message 字段（供日志/告警诊断）。
     *
     * @param response Feign 返回的响应 Map
     * @return message；缺失时返回 null
     */
    public static String message(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object msg = response.get("message");
        return msg == null ? null : msg.toString();
    }
}

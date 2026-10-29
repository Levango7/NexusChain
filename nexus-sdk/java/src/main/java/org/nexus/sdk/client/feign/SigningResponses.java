package org.nexus.sdk.client.feign;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
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
 *
 * <p>2026-10-29（A2 §7.5 决策一）：新增「失败路径响应体」提取能力。签名服务的
 * 明确未动账失败以 <b>HTTP 非 2xx + 业务信封</b>表达（P0-2），消费方 Feign
 * 看到的是异常（或被熔断器转成降级），正常 {@code Map} 返回路径拿不到它。
 * {@link #statusCode(String)} 与 {@link #declaresNotExecuted(Integer)}
 * 供 wallet 侧把异常/降级里的 {@code statusCode} 抠出来做三态映射。</p>
 */
public final class SigningResponses {

    /** 签名服务成功状态码（与 TxController 的 statusCode 常量对齐）。 */
    public static final int STATUS_OK = 2000;

    /**
     * 大额签名转为待审批（PENDING）的状态码（与 TxController.pendingApprovalResponse 对齐）。
     *
     * <p>该响应<b>没有 {@code data} 字段</b>，txHash 提取必然为 null；调用方必须先用
     * {@link #approvalId(Map)} 取出 approvalId 并按"待审批"处理，不能当签名失败。</p>
     */
    public static final int STATUS_APPROVAL_REQUIRED = 2001;

    /**
     * 业务失败（TxController.fail / failResponse 及 GlobalExceptionHandler 的
     * BIZ_FAIL）：所有<b>预广播失败</b>——无签名密钥 / 公钥不匹配 / 地址非法 /
     * nonce 获取失败 / 交易构造失败 / 审批未通过等。签名侧在此之后<b>没有</b>广播，
     * A2 凭据也会被 {@code revertConsumedAfterFailedBroadcast} 退回（可重试）。
     */
    public static final int STATUS_BUSINESS_FAIL = 5000;

    /**
     * 并发执行冲突（TxController sign-approved 路径的 tryMarkExecuting 失败）：
     * 另一路抢走了执行权，<b>本次调用确定未动账</b>。
     */
    public static final int STATUS_EXECUTION_CONFLICT = 5001;

    /**
     * A2 业务凭据已被消费（HTTP 409 + statusCode=5003）：上次签名<b>可能已广播</b>，
     * 属「结果不明」而非「未动账」——调用方必须判 EXECUTED_UNVERIFIED。
     */
    public static final int STATUS_BUSINESS_APPROVAL_CONSUMED = 5003;

    /**
     * 参数非法（GlobalExceptionHandler BIZ_BAD_REQUEST，400 语义）：
     * 请求在执行前被拒，确定未动账。
     */
    public static final int STATUS_BAD_REQUEST = 4000;

    /**
     * 鉴权/角色拒绝（GlobalExceptionHandler BIZ_FORBIDDEN，403 语义）：
     * 请求在执行前被拒，确定未动账。
     */
    public static final int STATUS_FORBIDDEN = 4030;

    /** "data" 字段名（交易哈希所在键）。 */
    public static final String KEY_STATUS_CODE = "statusCode";
    public static final String KEY_DATA = "data";

    private static final Gson GSON = new Gson();
    private static final Type ENVELOPE_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private SigningResponses() {
    }

    /** 三态结果类别：已执行 / 待审批 / 明确失败。 */
    public enum OutcomeKind {
        /** 已签名并广播，拿到了交易哈希。 */
        EXECUTED,
        /** 大额签名转人工审批，尚未动账；调用方应持久化 approvalId 并稍后执行。 */
        AWAITING_APPROVAL,
        /** 明确失败（确定未动账，或是无法跟踪的响应）。 */
        FAILED
    }

    /**
     * {@link #classify} 的三态结果快照。
     *
     * @param kind       结果类别
     * @param txHash     交易哈希，仅 {@link OutcomeKind#EXECUTED} 非空
     * @param approvalId 审批请求 ID，仅 {@link OutcomeKind#AWAITING_APPROVAL} 非空
     * @param message    失败原因或服务端 message（诊断用）
     * @param statusCode 签名服务返回的 statusCode（响应为 null 时为 null）
     */
    public record Outcome(OutcomeKind kind, String txHash, String approvalId,
                          String message, Integer statusCode) {

        public boolean isExecuted() {
            return kind == OutcomeKind.EXECUTED;
        }

        public boolean isAwaitingApproval() {
            return kind == OutcomeKind.AWAITING_APPROVAL;
        }

        public boolean isFailed() {
            return kind == OutcomeKind.FAILED;
        }
    }

    /**
     * 把签名服务响应归入三态：已执行 / 待审批 / 失败。
     *
     * <p><b>调用方应优先使用本方法而不是 {@link #txHash}。</b>2001（待审批）没有
     * {@code data} 字段，用 {@code txHash(...) == null} 判定会把"等审批"误判成
     * "签名失败"——这是执行腿断链的根因。</p>
     *
     * <p>边界语义：</p>
     * <ul>
     *   <li>null（fallback 降级）→ FAILED（确定未动账）</li>
     *   <li>2001 且带非空 approvalId → AWAITING_APPROVAL</li>
     *   <li>2001 但缺 approvalId → FAILED（无法跟踪，不能当待审批）</li>
     *   <li>2000 且 data 为非空哈希 → EXECUTED</li>
     *   <li>2000 但 data 缺失/空白、其他状态码、非法状态码 → FAILED</li>
     * </ul>
     *
     * @param response Feign 返回的响应 Map（可能为 null）
     * @return 三态结果（永不为 null）
     */
    public static Outcome classify(Map<String, Object> response) {
        if (response == null) {
            return new Outcome(OutcomeKind.FAILED, null, null,
                    "signing service unavailable", null);
        }
        Integer code = statusCode(response);
        if (code != null && code == STATUS_APPROVAL_REQUIRED) {
            String approvalId = approvalId(response);
            if (approvalId == null) {
                return new Outcome(OutcomeKind.FAILED, null, null,
                        "approval required but approvalId missing", code);
            }
            return new Outcome(OutcomeKind.AWAITING_APPROVAL, null, approvalId,
                    message(response), code);
        }
        if (code != null && code == STATUS_OK) {
            Object data = response.get(KEY_DATA);
            if (data != null) {
                String txHash = data.toString();
                if (!txHash.isBlank()) {
                    return new Outcome(OutcomeKind.EXECUTED, txHash, null,
                            message(response), code);
                }
            }
            String msg = message(response);
            return new Outcome(OutcomeKind.FAILED, null, null,
                    msg == null ? "statusCode=2000 but data is missing or blank" : msg, code);
        }
        String msg = message(response);
        return new Outcome(OutcomeKind.FAILED, null, null,
                msg == null ? "signing failed (statusCode=" + code + ")" : msg, code);
    }

    /**
     * 从 signTransfer 响应中提取交易哈希。
     *
     * @param response Feign 返回的响应 Map（可能为 null——fallback 降级）
     * @return 交易哈希；响应为 null / statusCode 非 2000 / data 缺失或空白
     *         时返回 null（调用方按签名失败处理）
     * @deprecated 用 {@link #classify(Map)} 区分三态后再取 {@link Outcome#txHash()}。
     *             本方法无法区分"待审批（2001）"与"签名失败"，继续使用会把
     *             大额审批请求误记为 FAILED。
     */
    @Deprecated
    public static String txHash(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Integer code = statusCode(response);
        if (code == null || code != STATUS_OK) {
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
     * 响应是否为"大额签名待人工审批"（{@code statusCode=2001}）。
     *
     * <p>与 {@link #txHash} 互斥：2001 没有 {@code data}，用 {@link #txHash} 判断会
     * 得到 null 并被误读成签名失败——这正是执行腿断链的根因。</p>
     */
    public static boolean isApprovalRequired(Map<String, Object> response) {
        Integer code = statusCode(response);
        return code != null && code == STATUS_APPROVAL_REQUIRED;
    }

    /**
     * 从待审批响应中提取 approvalId。
     *
     * @param response Feign 返回的响应 Map
     * @return approvalId；响应不是 2001 或字段缺失/空白时返回 null
     */
    public static String approvalId(Map<String, Object> response) {
        if (!isApprovalRequired(response)) {
            return null;
        }
        Object id = response.get("approvalId");
        if (id == null) {
            return null;
        }
        String approvalId = id.toString();
        return approvalId.isBlank() ? null : approvalId;
    }

    /**
     * 从审批状态查询响应（{@code GET /signing/approvals/{id}}）中提取审批状态字符串。
     *
     * <p>返回信封形状 {@code {statusCode:2000, data:{requestId, status, expired, ...}}}；
     * 审批已不存在时 HTTP 404（Feign 降级返回 null）。</p>
     *
     * @param response 审批状态查询响应 Map（可能为 null——404/降级）
     * @return 状态字符串（{@code PENDING/APPROVED/REJECTED/EXPIRED/EXECUTING/EXECUTED}）；
     *         响应为 null、信封非法或 {@code data.status} 缺失时返回 {@code null}
     */
    public static String approvalStatus(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Integer code = statusCode(response);
        if (code == null || code != STATUS_OK) {
            return null;
        }
        Object data = response.get(KEY_DATA);
        if (!(data instanceof Map<?, ?> dataMap)) {
            return null;
        }
        Object status = dataMap.get("status");
        if (status == null) {
            return null;
        }
        String value = status.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 提取响应的 statusCode（无则 {@code null}）。
     *
     * <p>2026-10-28 由 private 提升为 public：A2 腿（wallet）需要在 classify 的
     * 三态之上细分 statusCode（如 5003「业务凭据已消费」→ EXECUTED_UNVERIFIED），
     * 调用方不应各自散落 map.get("statusCode") 解析。</p>
     */
    public static Integer statusCode(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object status = response.get(KEY_STATUS_CODE);
        // Jackson 反序列化数字默认为 Integer/Long；兼容 Number 与字符串两种形态
        if (status instanceof Number n) {
            return n.intValue();
        }
        if (status != null) {
            try {
                return Integer.parseInt(status.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从签名服务响应中的 message 字段（供日志/告警诊断）。
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

    /**
     * 把失败路径的响应体 JSON 字符串解析成信封 Map（供 {@link #statusCode(Map)}
     * 等既有提取器复用）。
     *
     * <p>适用于签名服务以 HTTP 非 2xx 返回的业务信封（P0-2：业务失败带非 2xx
     * 状态码，响应体保持 {@code {"statusCode":…,"message":…,"data":""}} 形状），
     * 消费方只能从 Feign 异常的响应体（或熔断降级的 cause）里拿到它。</p>
     *
     * @param jsonEnvelope 响应体字符串（可能为 null / 空 / 非 JSON）
     * @return 信封 Map；入参为 null/空或无法按 JSON 对象解析时返回 null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> envelope(String jsonEnvelope) {
        if (jsonEnvelope == null) {
            return null;
        }
        String trimmed = jsonEnvelope.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            Object parsed = GSON.fromJson(trimmed, ENVELOPE_TYPE);
            // Gson 把 JSON 对象解析为 Map；非对象（数组/字符串/数字）时为其它类型
            return parsed instanceof Map<?, ?> ? (Map<String, Object>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从失败路径的响应体 JSON 字符串中提取 statusCode（无则 {@code null}）。
     *
     * <p>与 {@link #statusCode(Map)} 同语义，只是入参为 Feign 异常响应体的
     * 字符串形态（{@code FeignException.content()} 等）。Number 与字符串两种
     * 形态都兼容。</p>
     *
     * @param jsonEnvelope 响应体字符串（可能为 null / 空 / 非 JSON）
     * @return statusCode；入参非法或缺失 statusCode 时返回 null
     */
    public static Integer statusCode(String jsonEnvelope) {
        return statusCode(envelope(jsonEnvelope));
    }

    /**
     * 判断「失败路径」上的 statusCode 是否<b>由签名服务契约确立为「本次明确未动账」</b>。
     *
     * <p><b>fail-closed 判据（A2 §7.5 决策一）</b>：只有契约明确声明「未动账」
     * 的码才能映射为 FAILED——</p>
     * <ul>
     *   <li>{@link #STATUS_BUSINESS_FAIL}（5000）：预广播失败（无密钥/地址非法/
     *       交易构造失败/审批未通过…），A2 凭据已被 signing 回退，可安全重试；</li>
     *   <li>{@link #STATUS_EXECUTION_CONFLICT}（5001）：并发冲突，本次未动账；</li>
     *   <li>{@link #STATUS_BAD_REQUEST}（4000）：参数非法，请求在执行前被拒；</li>
     *   <li>{@link #STATUS_FORBIDDEN}（4030）：鉴权/白名单拒绝，请求在执行前被拒。</li>
     * </ul>
     *
     * <p><b>不</b>属于确定性未动账的码（返回 false，调用方必须保持「结果不明」）：</p>
     * <ul>
     *   <li>{@link #STATUS_BUSINESS_APPROVAL_CONSUMED}（5003）：凭据已消费，上次
     *       签名<b>可能已广播</b>——交人工核对；</li>
     *   <li>一切未知码（含未来新增码）：语义未确立，降级为结果不明，禁止当 FAILED 重放。</li>
     * </ul>
     *
     * @param statusCode 失败响应体里的 statusCode（可为 null）
     * @return 仅当属于契约确立的「明确未动账」码时返回 true
     */
    public static boolean declaresNotExecuted(Integer statusCode) {
        if (statusCode == null) {
            return false;
        }
        return statusCode == STATUS_BUSINESS_FAIL
                || statusCode == STATUS_EXECUTION_CONFLICT
                || statusCode == STATUS_BAD_REQUEST
                || statusCode == STATUS_FORBIDDEN;
    }
}

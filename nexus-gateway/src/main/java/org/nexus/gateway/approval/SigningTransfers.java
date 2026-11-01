package org.nexus.gateway.approval;

import org.nexus.sdk.client.feign.SigningResponses;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * 调用方侧签名转账的统一入口（审批执行腿，2026-10-25 裁决 §四/§六）。
 *
 * <p>把"直接签名"与"走审批链接表"两条路径收敛成一个三态结果，避免各调用点重复实现
 * 解析又各自把 2001（待审批）误判成失败。</p>
 */
public final class SigningTransfers {

    private static final Logger log = LoggerFactory.getLogger(SigningTransfers.class);

    private SigningTransfers() {
    }

    /**
     * 构造幂等的业务请求标识：{@code execution:<类型>:<业务 ID>}。
     *
     * <p>同一笔业务重试必须落到同一个 key，否则会创建多条审批请求，
     * 审批通过后可能被重复执行。业务 ID 缺失时退化为调用方给的兜底标识。</p>
     */
    public static String requestKey(String requestType, String businessId, String fallbackId) {
        String id = businessId != null && !businessId.isBlank() ? businessId : fallbackId;
        return "execution:" + requestType + ":" + id;
    }

    /**
     * 提交一笔签名转账：装配了 {@link SigningApprovalExecutionService} 时走链接表
     * （大额转待审批、审批通过后由调用方推进执行）；未装配时退化为直接签名，
     * 此时 2001 无法跟踪，按 fail-closed 记为失败。
     *
     * @param service 审批执行服务（可为 null——手工装配/单测场景）
     * @param client  签名服务 Feign 客户端（仅在 service 为 null 时使用）
     * @return 三态结果（永不为 null）
     */
    public static SigningOutcome signOrAwait(SigningApprovalExecutionService service,
                                             SigningServiceFeignClient client,
                                             String requestKey,
                                             String requestType,
                                             String fromPubkey,
                                             String toPubkeyHash,
                                             BigDecimal amount,
                                             String currency) {
        if (service != null) {
            return service.signOrAwait(requestKey, requestType, fromPubkey, toPubkeyHash, amount, currency);
        }
        // 无审批执行服务（未装配）时的直签：调用抛异常同样属"结果不明"——
        // 异常点可能在广播之后（连接重置/读超时），不得记可重放的 FAILED。
        // 与有服务那条腿的 catch 分支同范式（SigningApprovalExecutionService.signOrAwait）。
        java.util.Map<String, Object> response;
        try {
            response = client.signTransfer(fromPubkey, toPubkeyHash, amount);
        } catch (RuntimeException e) {
            log.error("直签抛异常（无执行服务，结果不明，禁止当未动账重放）: requestKey={}, error={}",
                    requestKey, e.getMessage());
            return new SigningOutcome(ApprovalExecutionLink.Status.EXECUTION_UNKNOWN, null, null,
                    "signTransfer outcome unknown: " + e.getMessage());
        }
        return fromClassify(response, requestKey);
    }

    /**
     * 把签名服务的原始响应归入 {@link SigningOutcome}（无链接表参与）。
     *
     * <p>待审批但无执行服务可跟踪 ⇒ 明确报失败并打出 approvalId，
     * 让运维能在签名服务侧处理，而不是静默留下一个永不执行的审批请求。</p>
     *
     * <p><b>失败语义（2026-10-31 起与 {@code SigningApprovalExecutionService.signOrAwait}
     * 共用同一张 fail-closed 词表）</b>：只有契约确立的「明确未动账」码（5000/4000/4030）
     * 才落 FAILED；降级 null、2000 无哈希、5001、5002、5003 及未知码都属结果不明 ⇒
     * {@code EXECUTION_UNKNOWN}（调用方按人工核对处理，不得自动重放）。本方法没有链接表
     * 可依，因此 UNKNOWN 只体现在返回值上，不落库。</p>
     */
    public static SigningOutcome fromClassify(java.util.Map<String, Object> response, String requestKey) {
        SigningResponses.Outcome outcome = SigningResponses.classify(response);
        if (outcome.isExecuted()) {
            return new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, outcome.txHash(), null);
        }
        if (outcome.isAwaitingApproval()) {
            log.error("大额签名待审批，但审批执行服务未装配，无法跟踪执行腿: requestKey={}, approvalId={}",
                    requestKey, outcome.approvalId());
            return new SigningOutcome(ApprovalExecutionLink.Status.FAILED,
                    outcome.approvalId(), null, "approval execution service unavailable");
        }
        Integer code = outcome.statusCode();
        if (!SigningResponses.declaresNotExecuted(code)) {
            log.error("直签结果不明（无执行服务，禁止当未动账重放）: requestKey={}, statusCode={}, message={}",
                    requestKey, code, outcome.message());
            return new SigningOutcome(ApprovalExecutionLink.Status.EXECUTION_UNKNOWN, null, null,
                    "signTransfer outcome unknown: statusCode=" + code
                            + (outcome.message() == null ? "" : ", message=" + outcome.message()));
        }
        return new SigningOutcome(ApprovalExecutionLink.Status.FAILED, null, null, outcome.message());
    }
}

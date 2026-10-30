package org.nexus.gateway.approval;

import org.nexus.gateway.approval.ApprovalExecutionLink.Status;
import org.nexus.sdk.client.feign.SigningResponses;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

/**
 * 大额签名"审批 → 执行"的调用方侧状态机（gateway）。
 *
 * <p>signing-service 对超过阈值的转账只创建审批请求并返回
 * {@code statusCode=2001 + approvalId}，<b>不隐式动钱</b>。本类负责：</p>
 * <ol>
 *   <li>首次调用 {@link #signOrAwait}：直接签名成功则返回哈希；2001 则把
 *       {@code requestKey ↔ approvalId} 落 {@link ApprovalExecutionLink}；</li>
 *   <li>调用方重试（{@code signOrAwait} 同一 requestKey）或域内 reconciler（{@code refresh}）：
 *       查询审批状态，{@code APPROVED} 时调用 {@code /transfers/sign/approved}
 *       完成签名并保存 txHash；本类<b>不自行定时执行</b>——"谁发起、谁推进"，
 *       避免未接线的业务路径在审批通过后被隐式动钱；</li>
 *   <li>终态映射：拒绝/过期 → FAILED；签名已执行但无哈希 → EXECUTED_UNVERIFIED；
 *       执行结果不可判定 → EXECUTION_UNKNOWN（均禁止自动重试）。
 *       <b>第一腿同样适用该词表</b>：异常/降级/未携带「明确未动账」码
 *       （{@link SigningResponses#declaresNotExecuted(Integer)}）的响应一律
 *       EXECUTION_UNKNOWN，只有契约确立的未动账码（5000/4000/4030）才 FAILED；</li>
 *   <li><b>第一腿结果不明必须落链接</b>：写一行终态 EXECUTION_UNKNOWN（approvalId
 *       为空，V110）堵住同一 requestKey 的再次发起——重试在链接表顶部被短路返回，
 *       不会再次调用 signTransfer。<b>确定未动账</b>不落行，保持可安全重试。</li>
 * </ol>
 *
 * <h3>为什么状态机要区分"未执行/已执行但未知"</h3>
 * <p>signing-service 会把超时卡在 EXECUTING 的审批回退成 APPROVED（其 cleanup
 * 任务）。若调用方在执行超时后看到 APPROVED 就再次执行，而上一笔其实已经广播，
 * 就是二次打款。因此本类在<b>发出执行请求之前</b>先把链接置为 EXECUTING 并记录
 * {@code executionAttemptedAt}；此后只有两种情况能回到可执行：
 * 签名服务明确答复 {@code statusCode=5000}（确定未动账），或换新的 requestKey。
 * 其余一律停在 EXECUTING / EXECUTION_UNKNOWN，交人工核对。</p>
 *
 * <p>事务边界：本类的 HTTP 调用一律在数据库事务之外（repository 单次 save 自带
 * 短事务），避免把外部调用持在事务里。</p>
 *
 * <p>调用方：{@code DefaultOnChainExecutionChannel}（退款/结算/归集）与
 * {@link org.nexus.gateway.service.PaymentRefundApprovalReconciler}（直通退款）。</p>
 */
@Service
public class SigningApprovalExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SigningApprovalExecutionService.class);

    /** signing-service：签名成功 */
    private static final int CODE_OK = SigningResponses.STATUS_OK;
    /** signing-service：审批未通过，无法执行（明确未动账） */
    private static final int CODE_APPROVAL_NOT_READY = 5000;
    /** signing-service：审批状态已变更/并发被抢（结果不明） */
    private static final int CODE_CAS_CONFLICT = 5001;

    private static final int LAST_ERROR_MAX = 512;

    private final SigningServiceFeignClient signingClient;
    private final ApprovalExecutionLinkRepository repository;

    /** 审批等待上限：超过后仍无 PENDING 之外的进展，按过期失败处理（确定未动账）。 */
    private final Duration awaitingTimeout;

    /** EXECUTING 卡死上限：超过后转 EXECUTION_UNKNOWN，停止自动推进、交人工核对。 */
    private final Duration executingTimeout;

    public SigningApprovalExecutionService(SigningServiceFeignClient signingClient,
                                           ApprovalExecutionLinkRepository repository,
                                           @Value("${nexus.gateway.approval.awaiting-timeout:PT2H}")
                                           Duration awaitingTimeout,
                                           @Value("${nexus.gateway.approval.executing-timeout:PT1H}")
                                           Duration executingTimeout) {
        this.signingClient = signingClient;
        this.repository = repository;
        this.awaitingTimeout = awaitingTimeout;
        this.executingTimeout = executingTimeout;
    }

    /**
     * 签名或进入审批等待。
     *
     * <p>幂等：同一 {@code requestKey} 只创建一个审批请求；重复调用只推进既有链接，
     * 不会再次调用 {@code signTransfer}（避免重复创建审批请求）。</p>
     *
     * @param requestKey   业务请求标识（必填，唯一）
     * @param requestType  业务类型（PAYMENT/REFUND/SETTLEMENT/...）
     * @param fromPubkey   平台出款公钥
     * @param toPubkeyHash 收款方公钥哈希
     * @param amount       金额
     * @param currency     币种/资产（可为 null）
     * @return 当前结果（执行成功 / 待审批 / 确定未动账失败 / 结果不明——后两者分别映射
     *         {@link Status#FAILED}（仅契约确立的未动账码）与 {@link Status#EXECUTION_UNKNOWN}）
     */
    public SigningOutcome signOrAwait(String requestKey, String requestType, String fromPubkey,
                                      String toPubkeyHash, BigDecimal amount, String currency) {
        requireText(requestKey, "requestKey");
        requireText(requestType, "requestType");

        ApprovalExecutionLink existing = repository.findByRequestKey(requestKey).orElse(null);
        if (existing != null) {
            return advance(existing);
        }

        Map<String, Object> response;
        try {
            response = signingClient.signTransfer(fromPubkey, toPubkeyHash, amount);
        } catch (RuntimeException e) {
            // 结果不明：异常可能发生在广播之后（如读超时），不得当「确定未动账」FAILED 重放。
            // 与第二腿 executeApproved 的异常分支同范式——停结果不明，交人工核对；
            // 同时落阻塞链接，阻止同一 requestKey 的再次发起（资金安全不变量见类注释）。
            String reason = "signTransfer outcome unknown: " + e.getMessage();
            log.error("signTransfer 抛异常（结果不明，可能已广播，禁止自动重试）: "
                    + "requestKey={}, error={}", requestKey, e.getMessage(), e);
            return unknownFirstLeg(requestKey, requestType, fromPubkey, toPubkeyHash, amount,
                    currency, reason);
        }
        if (response == null) {
            // 降级（fallback 闸门返回 null）：信封未携带契约确立的未动账码——
            // 5001/未定义码/熔断打开/无响应体皆可能，结果不明，禁止当未动账 FAILED。
            String reason = "signTransfer outcome unknown: signing service degraded";
            log.error("signTransfer 降级（结果不明，禁止自动重试）: requestKey={}", requestKey);
            return unknownFirstLeg(requestKey, requestType, fromPubkey, toPubkeyHash, amount,
                    currency, reason);
        }

        SigningResponses.Outcome outcome = SigningResponses.classify(response);
        if (outcome.isAwaitingApproval()) {
            String approvalId = outcome.approvalId();
            ApprovalExecutionLink link = newLink(requestKey, requestType, approvalId,
                    fromPubkey, toPubkeyHash, amount, currency);
            try {
                repository.save(link);
            } catch (DataIntegrityViolationException race) {
                // 并发同 key：另一请求已先落库。以既有链接为准推进；本次多创建的审批请求
                // 不会被本表跟踪，也不会被执行（最坏是留下一条待审批记录，至多过期）。
                log.warn("并发创建审批链接，改用既有记录: requestKey={}", requestKey);
                ApprovalExecutionLink winner = repository.findByRequestKey(requestKey).orElse(null);
                if (winner == null) {
                    throw race;
                }
                return advance(winner);
            }
            log.info("大额签名待审批: requestKey={}, approvalId={}, amount={} {}",
                    requestKey, approvalId, amount, currency);
            return new SigningOutcome(Status.AWAITING_APPROVAL, approvalId, null, null);
        }

        if (outcome.isExecuted()) {
            return new SigningOutcome(Status.EXECUTED, null, outcome.txHash(), null);
        }
        // 非成功、非待审批：仅「契约确立的明确未动账」码（5000/4000/4030，见
        // SigningResponses.declaresNotExecuted）才允许 FAILED——此时资金确定未转出，
        // 调用方可以安全处理业务失败。其余（5001 并发被抢/5003 凭据可能已消费/
        // 未知码/契约残缺响应）一律结果不明，交人工核对，与第二腿范式一致。
        Integer code = outcome.statusCode();
        if (SigningResponses.declaresNotExecuted(code)) {
            log.warn("signTransfer 明确失败（未动账）: requestKey={}, statusCode={}, message={}",
                    requestKey, code, outcome.message());
            return new SigningOutcome(Status.FAILED, null, null,
                    outcome.message() == null ? "signTransfer failed" : outcome.message());
        }
        log.error("signTransfer 结果不明（不当未动账，禁止自动重试）: requestKey={}, statusCode={}, message={}",
                requestKey, code, outcome.message());
        String reason = "signTransfer outcome unknown: statusCode=" + code
                + (outcome.message() == null ? "" : ", message=" + outcome.message());
        return unknownFirstLeg(requestKey, requestType, fromPubkey, toPubkeyHash, amount,
                currency, reason);
    }

    /**
     * 首腿（直接签名）结果不明：落一条<b>终态 EXECUTION_UNKNOWN</b> 链接（approvalId 为空），
     * 以 {@code request_key} 唯一键堵住同一 requestKey 的<b>再次发起签名</b>。
     *
     * <p>为什么必须落：阻塞重试。请求方重试（同一 requestKey 再次调用本类，或域内
     * reconciler 的 {@code refresh}）必然先命中这行终态链接并原样返回 EXECUTION_UNKNOWN——
     * 若首次签名其实已广播，重试就是二次广播双花。</p>
     *
     * <p>approvalId 为空是合法状态（V110）：首腿是直接签名路径，信封里本就没有
     * approvalId；该行<b>不对应任何审批</b>，只是"这里发生过一次签名尝试、结果不明"的
     * 闸口。终态链接在 {@code advance()} 顶部即被短路，不会被查询审批状态。</p>
     *
     * <p>并发同 key：另一路已先落库时以既有链接为准（同 2001 分支范式）。此时本路
     * 多出来的签名请求已成事实，链接状态以后落库者为准。</p>
     */
    private SigningOutcome unknownFirstLeg(String requestKey, String requestType, String fromPubkey,
                                           String toPubkeyHash, BigDecimal amount, String currency,
                                           String reason) {
        ApprovalExecutionLink link = newLink(requestKey, requestType, null,
                fromPubkey, toPubkeyHash, amount, currency);
        link.setStatus(Status.EXECUTION_UNKNOWN);
        link.setLastError(truncate(reason));
        try {
            ApprovalExecutionLink persisted = repository.save(link);
            log.error("首腿结果不明，已落 EXECUTION_UNKNOWN 阻塞链接（禁止再次签名）: "
                    + "requestKey={}, reason={}", requestKey, reason);
            return outcome(persisted);
        } catch (DataIntegrityViolationException race) {
            log.warn("首腿结果不明，链接已被同 key 占用: requestKey={}", requestKey);
            ApprovalExecutionLink winner = repository.findByRequestKey(requestKey).orElse(null);
            if (winner == null) {
                throw race;
            }
            return outcome(winner);
        }
    }

    /**
     * 推进一条既有链接（按 requestKey 重新读取，避免使用过期快照）。
     *
     * @param requestKey 业务请求标识
     * @return 推进后的结果；链接不存在时返回 FAILED（不得当作成功）
     */
    public SigningOutcome refresh(String requestKey) {
        ApprovalExecutionLink link = repository.findByRequestKey(requestKey).orElse(null);
        if (link == null) {
            return new SigningOutcome(Status.FAILED, null, null,
                    "approval execution link not found: " + requestKey);
        }
        return advance(link);
    }

    /** 查询链接当前状态（只读，不推进）。 */
    public SigningOutcome peek(String requestKey) {
        ApprovalExecutionLink link = repository.findByRequestKey(requestKey).orElse(null);
        return link == null ? null : outcome(link);
    }

    // --- 状态机 ---

    private SigningOutcome advance(ApprovalExecutionLink link) {
        if (link.getStatus().isTerminal()) {
            return outcome(link);
        }

        Map<String, Object> statusResponse;
        try {
            statusResponse = signingClient.getApprovalStatus(link.getApprovalId());
        } catch (RuntimeException e) {
            log.warn("审批状态查询失败: requestKey={}, approvalId={}, error={}",
                    link.getRequestKey(), link.getApprovalId(), e.getMessage());
            return touch(link, link.getStatus(), "getApprovalStatus failed: " + e.getMessage());
        }
        if (statusResponse == null) {
            return timeoutIfAwaitingTooLong(link, "signing service unavailable for status query");
        }

        Map<String, Object> data = asMap(statusResponse.get("data"));
        String approvalStatus = data == null ? null : asText(data.get("status"));
        if (approvalStatus == null) {
            return touch(link, link.getStatus(), "approval status missing in response");
        }

        LocalDateTime now = LocalDateTime.now();
        switch (approvalStatus.toUpperCase(Locale.ROOT)) {
            case "PENDING" -> {
                if (Boolean.TRUE.equals(asBoolean(data.get("expired")))) {
                    return fail(link, "approval expired");
                }
                return timeoutIfAwaitingTooLong(link, "approval still PENDING beyond wait timeout");
            }
            case "APPROVED" -> {
                // 已经发出过执行请求又看到 APPROVED：只可能是签名服务把超时的 EXECUTING
                // 回退了。上一笔可能已广播，绝不再次执行。
                if (link.getStatus() == Status.EXECUTING || link.getExecutionAttemptedAt() != null) {
                    return markManual(link, Status.EXECUTION_UNKNOWN,
                            "signing reported APPROVED after an execution attempt; "
                                    + "manual verification required (possible revert of a broadcast)");
                }
                return executeApproved(link, now);
            }
            case "EXECUTING" -> {
                if (link.getStatus() == Status.EXECUTING && isExecutingStale(link, now)) {
                    return markManual(link, Status.EXECUTION_UNKNOWN,
                            "execution stuck in EXECUTING beyond " + executingTimeout.toMinutes()
                                    + " minutes; manual verification required");
                }
                return touch(link, Status.EXECUTING, null);
            }
            case "EXECUTED" -> {
                if (link.getTxHash() != null) {
                    return touch(link, Status.EXECUTED, null);
                }
                log.error("签名服务报告 EXECUTED，但调用方没有交易哈希: requestKey={}, approvalId={} — "
                                + "资金可能已转出，禁止自动重试，请人工核对链上交易",
                        link.getRequestKey(), link.getApprovalId());
                return markManual(link, Status.EXECUTED_UNVERIFIED,
                        "signing reported EXECUTED but caller has no txHash");
            }
            case "REJECTED" -> {
                return link.getStatus() == Status.EXECUTING
                        ? markManual(link, Status.EXECUTION_UNKNOWN,
                        "signing reported REJECTED after an execution attempt")
                        : fail(link, "approval rejected");
            }
            case "EXPIRED" -> {
                return link.getStatus() == Status.EXECUTING
                        ? markManual(link, Status.EXECUTION_UNKNOWN,
                        "signing reported EXPIRED after an execution attempt")
                        : fail(link, "approval expired");
            }
            default -> {
                return touch(link, link.getStatus(), "unknown approval status: " + approvalStatus);
            }
        }
    }

    private SigningOutcome executeApproved(ApprovalExecutionLink link, LocalDateTime now) {
        // 先落"已尝试执行"标记，再发 HTTP：进程崩溃时后续轮询不会把状态当作"从未执行"。
        link.setStatus(Status.EXECUTING);
        link.setExecutionAttemptedAt(now);
        link.setAttempts(link.getAttempts() + 1);
        link.setLastError(null);
        link = persist(link);

        Map<String, Object> response;
        try {
            response = signingClient.executeApproved(link.getApprovalId());
        } catch (RuntimeException e) {
            log.error("executeApproved 抛异常，执行结果未知（禁止自动重试）: requestKey={}, approvalId={}, error={}",
                    link.getRequestKey(), link.getApprovalId(), e.getMessage(), e);
            return touch(link, Status.EXECUTING, "executeApproved outcome unknown: " + e.getMessage());
        }
        if (response == null) {
            log.error("executeApproved 降级，执行结果未知（禁止自动重试）: requestKey={}, approvalId={}",
                    link.getRequestKey(), link.getApprovalId());
            return touch(link, Status.EXECUTING, "signing service unavailable during execution");
        }

        String txHash = SigningResponses.txHash(response);
        if (txHash != null) {
            link.setStatus(Status.EXECUTED);
            link.setTxHash(txHash);
            link.setExecutedAt(LocalDateTime.now());
            link.setLastError(null);
            log.info("审批执行完成: requestKey={}, approvalId={}, txHash={}",
                    link.getRequestKey(), link.getApprovalId(), txHash);
            return outcome(persist(link));
        }

        Integer code = statusCode(response);
        String message = SigningResponses.message(response);
        if (code != null && code == CODE_APPROVAL_NOT_READY) {
            // 5000 的契约是"审批未通过/签名失败，资金未转出"，签名服务同时把状态回退到
            // APPROVED ⇒ 可以安全重试。
            link.setStatus(Status.APPROVED);
            link.setExecutionAttemptedAt(null);
            link.setLastError(truncate(message == null
                    ? "executeApproved returned 5000" : message));
            log.warn("审批执行明确失败（未动账，可重试）: requestKey={}, approvalId={}, attempts={}, message={}",
                    link.getRequestKey(), link.getApprovalId(), link.getAttempts(), message);
            return outcome(persist(link));
        }

        // 5001（并发被抢）或任何非预期响应：结果不确定，停在 EXECUTING 由后续状态轮询收敛。
        link.setLastError(truncate("executeApproved ambiguous result: code=" + code
                + (message == null ? "" : ", message=" + message)));
        log.warn("审批执行结果不确定，等待状态轮询: requestKey={}, code={}, message={}",
                link.getRequestKey(), code, message);
        return outcome(persist(link));
    }

    /** PENDING/查询失败累计超时 → 确定未动账，按 FAILED 收口（避免永久挂起）。 */
    private SigningOutcome timeoutIfAwaitingTooLong(ApprovalExecutionLink link, String reason) {
        if (link.getStatus() == Status.AWAITING_APPROVAL
                && link.getCreatedAt() != null
                && link.getCreatedAt().plus(awaitingTimeout).isBefore(LocalDateTime.now())) {
            return fail(link, "approval wait timeout after " + awaitingTimeout.toMinutes()
                    + " minutes (" + reason + ")");
        }
        return touch(link, link.getStatus(), null);
    }

    private boolean isExecutingStale(ApprovalExecutionLink link, LocalDateTime now) {
        LocalDateTime attemptedAt = link.getExecutionAttemptedAt();
        return attemptedAt != null && attemptedAt.plus(executingTimeout).isBefore(now);
    }

    private SigningOutcome fail(ApprovalExecutionLink link, String reason) {
        link.setStatus(Status.FAILED);
        link.setLastError(truncate(reason));
        log.warn("审批执行链接失败（确定未动账）: requestKey={}, reason={}",
                link.getRequestKey(), reason);
        return outcome(persist(link));
    }

    private SigningOutcome markManual(ApprovalExecutionLink link, Status status, String reason) {
        link.setStatus(status);
        link.setLastError(truncate(reason));
        log.error("审批执行结果需人工核对: requestKey={}, approvalId={}, status={}, reason={}",
                link.getRequestKey(), link.getApprovalId(), status, reason);
        return outcome(persist(link));
    }

    private SigningOutcome touch(ApprovalExecutionLink link, Status status, String error) {
        link.setStatus(status);
        if (error != null) {
            link.setLastError(truncate(error));
        }
        return outcome(persist(link));
    }

    /**
     * 保存并处理多副本并发：后提交者遇到乐观锁冲突时以库里的行为准；
     * 若本副本拿到了 txHash 而库里没有，则把哈希补写进去（哈希永不丢失）。
     */
    private ApprovalExecutionLink persist(ApprovalExecutionLink link) {
        try {
            return repository.save(link);
        } catch (OptimisticLockingFailureException conflict) {
            ApprovalExecutionLink latest = repository.findByRequestKey(link.getRequestKey()).orElse(null);
            if (latest == null) {
                throw conflict;
            }
            if (link.getTxHash() != null && latest.getTxHash() == null) {
                latest.setTxHash(link.getTxHash());
                latest.setStatus(Status.EXECUTED);
                latest.setExecutedAt(link.getExecutedAt());
                latest.setLastError(null);
                try {
                    return repository.save(latest);
                } catch (OptimisticLockingFailureException again) {
                    return repository.findByRequestKey(link.getRequestKey()).orElse(latest);
                }
            }
            log.info("审批执行链接并发推进，采用库中最新状态: requestKey={}, status={}",
                    latest.getRequestKey(), latest.getStatus());
            return latest;
        }
    }

    private ApprovalExecutionLink newLink(String requestKey, String requestType, String approvalId,
                                          String fromPubkey, String toPubkeyHash,
                                          BigDecimal amount, String currency) {
        ApprovalExecutionLink link = new ApprovalExecutionLink();
        link.setRequestKey(requestKey);
        link.setRequestType(requestType);
        link.setApprovalId(approvalId);
        link.setFromPubkey(fromPubkey);
        link.setToPubkeyHash(toPubkeyHash);
        link.setAmount(amount);
        link.setCurrency(currency);
        link.setStatus(Status.AWAITING_APPROVAL);
        link.setAttempts(0);
        return link;
    }

    private static SigningOutcome outcome(ApprovalExecutionLink link) {
        return new SigningOutcome(link.getStatus(), link.getApprovalId(),
                link.getTxHash(), link.getLastError());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.parseBoolean(value.toString());
    }

    private static Integer statusCode(Map<String, Object> response) {
        Object code = response.get(SigningResponses.KEY_STATUS_CODE);
        if (code instanceof Number number) {
            return number.intValue();
        }
        if (code != null) {
            try {
                return Integer.parseInt(code.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= LAST_ERROR_MAX) {
            return value;
        }
        return value.substring(0, LAST_ERROR_MAX);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}

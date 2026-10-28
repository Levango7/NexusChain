package org.nexus.signing.business;

import org.nexus.sdk.signing.BusinessApprovalPayloads;
import org.nexus.signing.audit.AuditEvent;
import org.nexus.signing.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A2 业务批准凭据登记 / 消费服务（ADR-036 §2.2，2026-10-28 立项实施）。
 *
 * <p><b>登记</b>（wallet → signing）：幂等键 = {@code businessApprovalId}；
 * 同单号同 {@code payloadHash} ⇒ 幂等返回既有行；同单号不同哈希 ⇒ 5002 拒绝
 * （防"同单号换载荷"）。登记是纯 DB 写，<b>不触发任何资金动作</b>。</p>
 *
 * <p><b>消费</b>（signTransfer 携凭据时）：按请求参数<b>重算</b> canonical 摘要并与
 * 登记值比对——摘要覆盖 fromPubkey / toPubkeyHash / 归一金额 / 单号，比对通过即等价于
 * 载荷一致。CAS（{@code WHERE state='REGISTERED'}）保证单次消费。四种不消费情形
 * （{@link Reason}）由调用方分派：<b>仅 ABSENT / HASH_MISMATCH / EXPIRED 回落既有审批流</b>
 * （有签名人人工闸门兜底）；<b>ALREADY_CONSUMED 必须返回 5003 终止</b>——凭据已被消费
 * 意味着上次签名可能已广播，若此时再给一条人审通道，同一笔提现就有了二次放款路径
 * （#294/#300 同源原则："可能已出"不得当"没出"重放）。</p>
 */
@Service
public class BusinessApprovalService {

    private static final Logger log = LoggerFactory.getLogger(BusinessApprovalService.class);

    /** signing 侧「凭据已消费（可能已广播）」状态码——wallet A2 腿必须判 EXECUTED_UNVERIFIED。 */
    public static final int STATUS_ALREADY_CONSUMED = 5003;

    private final SigningBusinessApprovalRepository repository;

    /**
     * 审计（可空：测试环境降级 no-op，与 TxController 同款约定）。
     *
     * <p><b>为什么用 setter 注入</b>：本类<b>只有一个构造器</b>——多构造器且无
     * {@code @Autowired} 会让 Spring 找不到默认构造器，整个上下文起不来
     * （2026-10-11 网关 {@code No default constructor found} 事故同款；
     * 本次实现首轮就在 signing 的 AuthIT 上复现了同一坑）。
     * 可空依赖走 setter，构造器保持唯一。</p>
     */
    private AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLogService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public BusinessApprovalService(SigningBusinessApprovalRepository repository) {
        this.repository = repository;
    }

    /** 登记请求值对象（controller 层已完成基础格式校验）。 */
    public record Registration(String businessApprovalId, String payloadHash, BigDecimal amount,
                               String currency, String fromPubkey, String toPubkeyHash,
                               Instant expiresAt, String approversJson) {
    }

    /** 消费结果：{@code consumed=true} 拿到消费权；否则带 {@link Reason}。 */
    public record ConsumeAttempt(boolean consumed, Reason reason, String storedPayloadHash) {
    }

    /** 不消费的原因（调用方分派：仅 ALREADY_CONSUMED 不得回落）。 */
    public enum Reason {
        /** 凭据不存在（未登记）——回落旧流程。 */
        ABSENT,
        /** 载荷摘要不匹配（登记的是另一笔载荷）——回落旧流程（有人审兜底）。 */
        HASH_MISMATCH,
        /** 凭据已过期——回落旧流程（有人审兜底）。 */
        EXPIRED,
        /** 凭据已被消费——<b>不得回落</b>，返回 5003 交人工核对。 */
        ALREADY_CONSUMED
    }

    /**
     * 登记一笔业务批准凭据（幂等）。
     *
     * <p>校验链：单号/哈希/金额/from/to/expiresAt 非法 ⇒ {@link IllegalArgumentException}；
     * 按 body 字段<b>重算</b> canonical 摘要并与提交的 {@code payloadHash} 比对，不一致 ⇒
     * {@link PayloadHashConflictException}（登记体自身不一致，防登记入口被塞假哈希）。</p>
     *
     * @return 响应 data：{@code {businessApprovalId, state, idempotent}}
     */
    @Transactional
    public Map<String, Object> register(Registration reg) {
        validate(reg);
        String recomputed = BusinessApprovalPayloads.canonicalHash(
                reg.fromPubkey(), reg.toPubkeyHash(), reg.amount(), reg.businessApprovalId());
        if (!recomputed.equalsIgnoreCase(reg.payloadHash())) {
            audit(AuditEvent.Outcome.FAILURE, reg.businessApprovalId(), "register_hash_mismatch");
            throw new PayloadHashConflictException(
                    "payloadHash does not match the registration body fields (expected " + recomputed + ")");
        }

        Optional<SigningBusinessApprovalEntity> existing =
                repository.findByBusinessApprovalId(reg.businessApprovalId());
        if (existing.isPresent()) {
            SigningBusinessApprovalEntity row = existing.get();
            if (!recomputed.equalsIgnoreCase(row.getPayloadHash())) {
                audit(AuditEvent.Outcome.FAILURE, reg.businessApprovalId(), "register_id_conflict");
                throw new PayloadHashConflictException(
                        "businessApprovalId already registered with a different payloadHash");
            }
            // 幂等：同单号同载荷。已 CONSUMED 的行原样返回（消费闸门自会拒绝后续 signTransfer）。
            log.info("A2 凭据登记幂等命中: businessApprovalId={}, state={}",
                    reg.businessApprovalId(), row.getState());
            return data(reg.businessApprovalId(), row.getState(), true);
        }

        SigningBusinessApprovalEntity row = new SigningBusinessApprovalEntity();
        row.setBusinessApprovalId(reg.businessApprovalId());
        row.setPayloadHash(recomputed);
        row.setAmount(reg.amount());
        row.setCurrency(reg.currency() == null || reg.currency().isBlank() ? "USDT" : reg.currency());
        row.setFromPubkey(reg.fromPubkey());
        row.setToPubkeyHash(reg.toPubkeyHash());
        row.setApproversJson(reg.approversJson());
        row.setState("REGISTERED");
        row.setExpiresAt(LocalDateTime.ofInstant(reg.expiresAt(), ZoneOffset.UTC));
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setVersion(0L);
        repository.save(row);
        audit(AuditEvent.Outcome.SUCCESS, reg.businessApprovalId(), "registered");
        log.info("A2 凭据已登记: businessApprovalId={}, amount={}, expiresAt={}",
                reg.businessApprovalId(), reg.amount(), reg.expiresAt());
        return data(reg.businessApprovalId(), "REGISTERED", false);
    }

    /**
     * 尝试消费凭据（signTransfer 携 {@code businessApprovalId} 时的入口）。
     *
     * <p>按<b>本次请求的实际参数</b>重算摘要比对（不是信任登记体的字段），
     * 通过后 CAS 消费。必须在事务内（条件更新需要）。</p>
     */
    @Transactional
    public ConsumeAttempt tryConsume(String businessApprovalId, String fromPubkey,
                                     String toPubkeyHash, BigDecimal amount) {
        Optional<SigningBusinessApprovalEntity> found = repository.findByBusinessApprovalId(businessApprovalId);
        if (found.isEmpty()) {
            audit(AuditEvent.Outcome.DENIED, businessApprovalId, "consume_absent");
            return new ConsumeAttempt(false, Reason.ABSENT, null);
        }
        SigningBusinessApprovalEntity row = found.get();
        String recomputed = BusinessApprovalPayloads.canonicalHash(
                fromPubkey, toPubkeyHash, amount, businessApprovalId);
        if (!recomputed.equalsIgnoreCase(row.getPayloadHash())) {
            // 登记的载荷与本次签名载荷不一致：不消费、不豁免（回落有人审的旧流程）
            audit(AuditEvent.Outcome.DENIED, businessApprovalId, "consume_hash_mismatch");
            log.warn("A2 凭据载荷不匹配，拒绝豁免: businessApprovalId={}", businessApprovalId);
            return new ConsumeAttempt(false, Reason.HASH_MISMATCH, row.getPayloadHash());
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            audit(AuditEvent.Outcome.DENIED, businessApprovalId, "consume_expired");
            return new ConsumeAttempt(false, Reason.EXPIRED, row.getPayloadHash());
        }
        if ("CONSUMED".equals(row.getState())) {
            audit(AuditEvent.Outcome.DENIED, businessApprovalId, "consume_already_consumed");
            log.error("A2 凭据已被消费，本次签名拒绝且不得回落人审（可能已广播）: businessApprovalId={}",
                    businessApprovalId);
            return new ConsumeAttempt(false, Reason.ALREADY_CONSUMED, row.getPayloadHash());
        }
        int won = repository.consumeIfRegistered(businessApprovalId, LocalDateTime.now(ZoneOffset.UTC));
        if (won != 1) {
            // 理论上仅发生在"读取后状态被并发改写"——按已消费处理（fail-closed）
            audit(AuditEvent.Outcome.DENIED, businessApprovalId, "consume_lost_race");
            return new ConsumeAttempt(false, Reason.ALREADY_CONSUMED, row.getPayloadHash());
        }
        audit(AuditEvent.Outcome.SUCCESS, businessApprovalId, "consumed");
        log.info("A2 凭据消费成功，跳过 signing 大额审批: businessApprovalId={}, amount={}",
                businessApprovalId, amount);
        return new ConsumeAttempt(true, null, row.getPayloadHash());
    }

    /**
     * 广播失败后的凭据回退（CONSUMED → REGISTERED）。
     *
     * <p>doSignAndBroadcast 的失败路径资金未转出（无签名键/地址非法/nonce 失败/构造失败），
     * 凭据必须可重试——否则一次渠道故障烧掉一张已批准的凭据。回退失败（并发已变）⇒
     * 保持 CONSUMED 交人工核对（fail-closed 方向）。</p>
     */
    @Transactional
    public void revertConsumedAfterFailedBroadcast(String businessApprovalId) {
        int reverted = repository.revertConsumed(businessApprovalId);
        if (reverted == 1) {
            audit(AuditEvent.Outcome.SUCCESS, businessApprovalId, "revert_after_failed_broadcast");
            log.warn("A2 凭据已回退（广播失败，资金未转出，凭据可重试）: businessApprovalId={}",
                    businessApprovalId);
        } else {
            audit(AuditEvent.Outcome.FAILURE, businessApprovalId, "revert_skipped_state_changed");
            log.error("A2 凭据回退未执行（状态已变，保持 CONSUMED 交人工核对）: businessApprovalId={}",
                    businessApprovalId);
        }
    }

    private void validate(Registration reg) {
        requireText(reg.businessApprovalId(), "businessApprovalId", 64);
        requireText(reg.payloadHash(), "payloadHash", 64);
        if (reg.payloadHash() != null && !reg.payloadHash().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("payloadHash must be 64-char hex");
        }
        if (reg.amount() == null || reg.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        requireText(reg.fromPubkey(), "fromPubkey", 512);
        requireText(reg.toPubkeyHash(), "toPubkeyHash", 128);
        if (reg.expiresAt() == null) {
            throw new IllegalArgumentException("expiresAt is required (ISO-8601 instant)");
        }
    }

    private void requireText(String value, String name, int maxLen) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > maxLen) {
            throw new IllegalArgumentException(name + " exceeds max length " + maxLen);
        }
    }

    private Map<String, Object> data(String businessApprovalId, String state, boolean idempotent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessApprovalId", businessApprovalId);
        m.put("state", state);
        m.put("idempotent", idempotent);
        return m;
    }

    private void audit(AuditEvent.Outcome outcome, String businessApprovalId, String reason) {
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.log(AuditEvent.builder(AuditEvent.Type.BUSINESS_APPROVAL, outcome,
                            AuditLogService.resolveActor("anonymous"))
                    .target(businessApprovalId)
                    .detail("reason", reason)
                    .build());
        } catch (RuntimeException e) {
            log.warn("A2 审计写失败（不影响业务）: reason={}, error={}", reason, e.getMessage());
        }
    }

    /** 登记载荷不一致（同单号异哈希 / body 与重算哈希不符）——controller 映射 5002。 */
    public static class PayloadHashConflictException extends RuntimeException {
        public PayloadHashConflictException(String message) {
            super(message);
        }
    }
}

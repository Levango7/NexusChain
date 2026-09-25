package org.nexus.gateway.risk.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 大额交易拦截服务 — 拦截超限额交易并管理审核流程。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #intercept} — 拦截超限额交易，创建拦截记录</li>
 *   <li>{@link #approve} — 审核通过，放行交易</li>
 *   <li>{@link #reject} — 审核驳回，阻断交易</li>
 *   <li>{@link #escalateTimeout} — 超时升级告警</li>
 * </ul>
 *
 * <p>拦截后进入 PENDING_REVIEW 状态，等待人工审核。
 * 超过 24 小时未审核的记录自动升级为 TIMEOUT_ESCALATED 告警状态。</p>
 */
@Service
public class LargeTransactionInterceptionService {

    private static final Logger log = LoggerFactory.getLogger(LargeTransactionInterceptionService.class);

    private final LargeTransactionInterceptionRepository interceptionRepository;

    public LargeTransactionInterceptionService(
            LargeTransactionInterceptionRepository interceptionRepository) {
        this.interceptionRepository = interceptionRepository;
    }

    // ==================== 拦截 ====================

    /**
     * 拦截超限额交易 — 创建拦截记录。
     *
     * <p>幂等检查：同一 riskEventId 已存在拦截记录则跳过。</p>
     *
     * @param merchantId  商户 ID
     * @param orderId     关联订单号
     * @param amount      交易金额
     * @param currency    币种
     * @param threshold   触发拦截的阈值
     * @param riskEventId 关联风控事件 ID
     * @return 拦截记录
     */
    @Transactional
    public LargeTransactionInterception intercept(Long merchantId, String orderId,
                                                    BigDecimal amount, String currency,
                                                    BigDecimal threshold, String riskEventId) {
        // 幂等检查：同一 riskEventId 已存在拦截记录
        if (riskEventId != null) {
            Optional<LargeTransactionInterception> existing = interceptionRepository.findByRiskEventId(riskEventId);
            if (existing.isPresent()) {
                log.info("大额交易拦截已存在（幂等跳过）: riskEventId={}, merchantId={}",
                        riskEventId, merchantId);
                return existing.get();
            }
        }

        LargeTransactionInterception interception = new LargeTransactionInterception();
        interception.setInterceptionId(generateInterceptionId());
        interception.setMerchantId(merchantId);
        interception.setOrderId(orderId);
        interception.setAmount(amount);
        interception.setCurrency(currency != null ? currency : "USD");
        interception.setThreshold(threshold);
        interception.setInterceptionStatus(InterceptionStatus.PENDING_REVIEW);
        interception.setRiskEventId(riskEventId);
        interception.setTimeoutAt(LocalDateTime.now().plusHours(24));

        interception = interceptionRepository.save(interception);
        log.info("大额交易已拦截: interceptionId={}, merchantId={}, amount={}, threshold={}",
                interception.getInterceptionId(), merchantId, amount, threshold);
        return interception;
    }

    // ==================== 审核 ====================

    /**
     * 审核通过 — 放行交易。
     *
     * @param interceptionId 拦截记录 ID
     * @param reviewerId     审核人 ID
     * @param reviewComment  审核意见
     * @return 更新后的拦截记录
     * @throws IllegalArgumentException 拦截记录不存在或状态非 PENDING_REVIEW
     */
    @Transactional
    public LargeTransactionInterception approve(String interceptionId, Long reviewerId, String reviewComment) {
        LargeTransactionInterception interception = findAndAssertPending(interceptionId);

        interception.setInterceptionStatus(InterceptionStatus.APPROVED);
        interception.setReviewerId(reviewerId);
        interception.setReviewComment(reviewComment);
        interception.setReviewedAt(LocalDateTime.now());

        interception = interceptionRepository.save(interception);
        log.info("大额交易审核通过: interceptionId={}, reviewerId={}", interceptionId, reviewerId);
        return interception;
    }

    /**
     * 审核驳回 — 阻断交易。
     *
     * @param interceptionId 拦截记录 ID
     * @param reviewerId     审核人 ID
     * @param reviewComment  审核意见
     * @return 更新后的拦截记录
     * @throws IllegalArgumentException 拦截记录不存在或状态非 PENDING_REVIEW
     */
    @Transactional
    public LargeTransactionInterception reject(String interceptionId, Long reviewerId, String reviewComment) {
        LargeTransactionInterception interception = findAndAssertPending(interceptionId);

        interception.setInterceptionStatus(InterceptionStatus.REJECTED);
        interception.setReviewerId(reviewerId);
        interception.setReviewComment(reviewComment);
        interception.setReviewedAt(LocalDateTime.now());

        interception = interceptionRepository.save(interception);
        log.info("大额交易审核驳回: interceptionId={}, reviewerId={}", interceptionId, reviewerId);
        return interception;
    }

    // ==================== 超时升级告警 ====================

    /**
     * 超时升级告警 — 将超过 24h 未审核的拦截记录升级为告警状态。
     *
     * @param interception 拦截记录
     * @return 更新后的拦截记录
     */
    @Transactional
    public LargeTransactionInterception escalateTimeout(LargeTransactionInterception interception) {
        interception.setInterceptionStatus(InterceptionStatus.TIMEOUT_ESCALATED);
        interception.setEscalatedAt(LocalDateTime.now());

        interception = interceptionRepository.save(interception);
        log.warn("大额交易拦截超时升级告警: interceptionId={}, merchantId={}, amount={}",
                interception.getInterceptionId(), interception.getMerchantId(), interception.getAmount());
        return interception;
    }

    // ==================== 查询方法 ====================

    /**
     * 查询商户的拦截记录列表。
     *
     * @param merchantId 商户 ID
     * @return 拦截记录列表
     */
    public List<LargeTransactionInterception> getByMerchantId(Long merchantId) {
        return interceptionRepository.findByMerchantId(merchantId);
    }

    /**
     * 按拦截记录 ID 查询。
     *
     * @param interceptionId 拦截记录 ID
     * @return 拦截记录（可能为空）
     */
    public Optional<LargeTransactionInterception> getByInterceptionId(String interceptionId) {
        return interceptionRepository.findByInterceptionId(interceptionId);
    }

    // ==================== 内部方法 ====================

    /**
     * 查找拦截记录并断言状态为 PENDING_REVIEW。
     */
    private LargeTransactionInterception findAndAssertPending(String interceptionId) {
        LargeTransactionInterception interception = interceptionRepository
                .findByInterceptionId(interceptionId)
                .orElseThrow(() -> new IllegalArgumentException("拦截记录不存在: " + interceptionId));

        if (interception.getInterceptionStatus() != InterceptionStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException(
                    "拦截记录状态非 PENDING_REVIEW，无法审核: " + interceptionId
                            + ", 当前状态=" + interception.getInterceptionStatus());
        }
        return interception;
    }

    /**
     * 生成拦截记录唯一标识。
     */
    private String generateInterceptionId() {
        return "LTI" + UUID.randomUUID().toString().replace("-", "");
    }
}
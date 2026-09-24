package org.nexus.gateway.orchestration.settlement;

import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.model.FinalityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 链上结算确认服务（Wave 9-C1-1）。
 *
 * <p>完整流程：支付完成 → 触发链上结算确认 → 查询链上确认状态 → 更新最终性状态。</p>
 *
 * <p>核心方法：
 * <ul>
 *   <li>{@link #initiateConfirmation(String, String)} — 支付完成后触发链上结算确认流程</li>
 *   <li>{@link #checkConfirmation(String)} — 查询链上确认状态（调用 ChainRpcClient 检查交易确认数）</li>
 *   <li>{@link #updateFinalityStatus(String, FinalityStatus)} — 确认达到阈值后更新最终性状态</li>
 *   <li>{@link #handleConfirmationTimeout(String)} — 确认超时处理（自动重试+告警）</li>
 * </ul>
 *
 * <p>定时任务：每 30 秒扫描 PENDING 状态的确认记录，自动查询链上状态并更新。</p>
 *
 * <p>超时处理：确认超过指定时间（默认 30 分钟）未达到所需确认数时，
 * 自动触发告警 + 重试查询；重试次数超过上限（默认 3 次）后标记为 FAILED。</p>
 *
 * @since Wave 9-C1-1 链上结算确认
 */
@Service
public class ChainSettlementConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ChainSettlementConfirmationService.class);

    private final ChainRpcClient chainRpc;
    private final FinalityService finalityService;
    private final SettlementConfirmationRecordRepository repository;

    /** 确认超时时间（分钟），默认 30 分钟 */
    private final long confirmationTimeoutMinutes;

    /** 最大重试次数，默认 3 次 */
    private final int maxRetryCount;

    public ChainSettlementConfirmationService(
            ChainRpcClient chainRpc,
            FinalityService finalityService,
            SettlementConfirmationRecordRepository repository,
            @Value("${nexus.settlement.confirmation-timeout-minutes:30}") long confirmationTimeoutMinutes,
            @Value("${nexus.settlement.max-retry-count:3}") int maxRetryCount) {
        this.chainRpc = chainRpc;
        this.finalityService = finalityService;
        this.repository = repository;
        this.confirmationTimeoutMinutes = confirmationTimeoutMinutes;
        this.maxRetryCount = maxRetryCount;
    }

    /**
     * 支付完成后触发链上结算确认流程。
     *
     * <p>创建一条 PENDING 状态的确认记录，记录支付 ID 和链上交易哈希，
     * 并立即执行一次链上状态查询。</p>
     *
     * @param paymentId 支付 ID
     * @param txHash    链上交易哈希
     * @return 创建的确认记录
     */
    @Transactional
    public SettlementConfirmationRecord initiateConfirmation(String paymentId, String txHash) {
        if (paymentId == null || paymentId.isEmpty()) {
            throw new IllegalArgumentException("paymentId must not be null or empty");
        }
        if (txHash == null || txHash.isEmpty()) {
            throw new IllegalArgumentException("txHash must not be null or empty");
        }

        // 幂等校验：同一支付 ID 不重复创建确认记录
        if (repository.existsByPaymentId(paymentId)) {
            log.info("Confirmation record already exists for paymentId={}, returning existing record", paymentId);
            return repository.findByPaymentId(paymentId).orElseThrow();
        }

        long requiredConfirmations = finalityService.getBlocksToFinalize();

        SettlementConfirmationRecord record = new SettlementConfirmationRecord();
        record.setPaymentId(paymentId);
        record.setTxHash(txHash);
        record.setConfirmations(0);
        record.setRequiredConfirmations(requiredConfirmations);
        record.setStatus(SettlementConfirmationStatus.PENDING);
        record.setRetryCount(0);
        record.setCreatedAt(Instant.now());
        record.setLastCheckedAt(Instant.now());

        record = repository.save(record);
        log.info("Settlement confirmation initiated: paymentId={}, txHash={}, requiredConfirmations={}",
                paymentId, txHash, requiredConfirmations);

        // 立即执行一次链上状态查询
        try {
            checkConfirmation(paymentId);
        } catch (RuntimeException e) {
            log.warn("Initial confirmation check failed for paymentId={}: {}", paymentId, e.getMessage());
        }

        return record;
    }

    /**
     * 查询链上确认状态。
     *
     * <p>调用 ChainRpcClient 检查交易确认数，更新确认记录的 confirmations 和 lastCheckedAt 字段。
     * 如果确认数达到阈值，自动将状态更新为 CONFIRMED 并调用 {@link #updateFinalityStatus}。</p>
     *
     * @param paymentId 支付 ID
     * @return 更新后的确认记录
     */
    @Transactional
    public SettlementConfirmationRecord checkConfirmation(String paymentId) {
        SettlementConfirmationRecord record = repository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("No confirmation record found for paymentId=" + paymentId));

        if (record.getStatus() == SettlementConfirmationStatus.CONFIRMED
                || record.getStatus() == SettlementConfirmationStatus.FAILED) {
            log.debug("Confirmation already {} for paymentId={}, skipping check",
                    record.getStatus(), paymentId);
            return record;
        }

        String txHash = record.getTxHash();
        record.setLastCheckedAt(Instant.now());

        try {
            // 使用 FinalityService 获取最终性信息（BFT 权重优先 + 确认数降级）
            FinalityService.FinalityInfo finalityInfo = finalityService.getFinality(txHash);
            long confirmations = finalityInfo.confirmations();
            record.setConfirmations(confirmations);

            if (finalityInfo.status() == FinalityStatus.FINALIZED) {
                record.setStatus(SettlementConfirmationStatus.CONFIRMED);
                record.setConfirmedAt(Instant.now());
                log.info("Settlement confirmed: paymentId={}, txHash={}, confirmations={}",
                        paymentId, txHash, confirmations);

                // 更新最终性状态
                updateFinalityStatus(paymentId, FinalityStatus.FINALIZED);
            } else if (finalityInfo.status() == FinalityStatus.UNKNOWN) {
                // 链不可达或交易未找到，检查是否超时
                checkTimeout(record);
            } else {
                // OPTIMISTIC 或 FINALIZING，继续等待
                log.debug("Settlement pending: paymentId={}, confirmations={}, required={}, finality={}",
                        paymentId, confirmations, record.getRequiredConfirmations(), finalityInfo.status());
            }
        } catch (RuntimeException e) {
            log.warn("Failed to check confirmation for paymentId={}: {}", paymentId, e.getMessage());
            record.setErrorMessage("Chain query failed: " + e.getMessage());
            checkTimeout(record);
        }

        return repository.save(record);
    }

    /**
     * 确认达到阈值后更新最终性状态。
     *
     * <p>此方法在确认数达到阈值时被自动调用，也可由外部系统手动调用
     * 以强制更新最终性状态（如 BFT 投票层直接通知）。</p>
     *
     * @param paymentId 支付 ID
     * @param newStatus 新的最终性状态
     */
    @Transactional
    public void updateFinalityStatus(String paymentId, FinalityStatus newStatus) {
        SettlementConfirmationRecord record = repository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("No confirmation record found for paymentId=" + paymentId));

        log.info("Updating finality status: paymentId={}, oldStatus={}, newStatus={}",
                paymentId, record.getStatus(), newStatus);

        if (newStatus == FinalityStatus.FINALIZED) {
            record.setStatus(SettlementConfirmationStatus.CONFIRMED);
            record.setConfirmedAt(Instant.now());
        }

        repository.save(record);
    }

    /**
     * 确认超时处理。
     *
     * <p>确认超过指定时间（默认 30 分钟）未达到所需确认数时：
     * <ol>
     *   <li>自动触发告警（日志 WARN 级别）</li>
     *   <li>自动重试查询链上状态</li>
     *   <li>重试次数超过上限（默认 3 次）后标记为 FAILED</li>
     * </ol>
     *
     * @param paymentId 支付 ID
     */
    @Transactional
    public void handleConfirmationTimeout(String paymentId) {
        SettlementConfirmationRecord record = repository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("No confirmation record found for paymentId=" + paymentId));

        if (record.getStatus() != SettlementConfirmationStatus.PENDING
                && record.getStatus() != SettlementConfirmationStatus.TIMED_OUT) {
            log.debug("Confirmation not in PENDING/TIMED_OUT state for paymentId={}, skipping timeout handling",
                    paymentId);
            return;
        }

        Duration elapsed = Duration.between(record.getCreatedAt(), Instant.now());
        if (elapsed.toMinutes() < confirmationTimeoutMinutes) {
            log.debug("Confirmation not yet timed out for paymentId={}, elapsed={}min, timeout={}min",
                    paymentId, elapsed.toMinutes(), confirmationTimeoutMinutes);
            return;
        }

        // 超时处理
        record.setStatus(SettlementConfirmationStatus.TIMED_OUT);
        record.setRetryCount(record.getRetryCount() + 1);
        record.setErrorMessage("Confirmation timed out after " + elapsed.toMinutes() + " minutes");

        log.warn("Settlement confirmation timed out: paymentId={}, txHash={}, retryCount={}, elapsed={}min",
                paymentId, record.getTxHash(), record.getRetryCount(), elapsed.toMinutes());

        if (record.getRetryCount() >= maxRetryCount) {
            record.setStatus(SettlementConfirmationStatus.FAILED);
            record.setErrorMessage("Confirmation failed after " + record.getRetryCount() + " retries (timeout: "
                    + confirmationTimeoutMinutes + "min each)");
            log.error("Settlement confirmation FAILED: paymentId={}, txHash={}, retryCount={}",
                    paymentId, record.getTxHash(), record.getRetryCount());
        } else {
            // 自动重试：重新查询链上状态
            log.info("Auto-retrying confirmation check for paymentId={}, retryCount={}",
                    paymentId, record.getRetryCount());
            try {
                FinalityService.FinalityInfo finalityInfo = finalityService.getFinality(record.getTxHash());
                long confirmations = finalityInfo.confirmations();
                record.setConfirmations(confirmations);
                record.setLastCheckedAt(Instant.now());

                if (finalityInfo.status() == FinalityStatus.FINALIZED) {
                    record.setStatus(SettlementConfirmationStatus.CONFIRMED);
                    record.setConfirmedAt(Instant.now());
                    record.setErrorMessage(null);
                    log.info("Settlement confirmed on retry: paymentId={}, confirmations={}",
                            paymentId, confirmations);
                    updateFinalityStatus(paymentId, FinalityStatus.FINALIZED);
                } else {
                    // 仍然未确认，保持 TIMED_OUT 状态等待下次扫描
                    record.setStatus(SettlementConfirmationStatus.TIMED_OUT);
                }
            } catch (RuntimeException e) {
                log.warn("Retry confirmation check failed for paymentId={}: {}", paymentId, e.getMessage());
                record.setErrorMessage("Retry failed: " + e.getMessage());
            }
        }

        repository.save(record);
    }

    /**
     * 定时任务：扫描 PENDING 状态的确认记录，自动查询链上状态。
     *
     * <p>每 30 秒执行一次，对每条 PENDING 记录调用 {@link #checkConfirmation(String)}，
     * 并检查是否超时需要调用 {@link #handleConfirmationTimeout(String)}。</p>
     */
    @Scheduled(fixedDelayString = "${nexus.settlement.scan-interval-ms:30000}")
    @Transactional
    public void scanPendingConfirmations() {
        List<SettlementConfirmationRecord> pendingRecords =
                repository.findByStatus(SettlementConfirmationStatus.PENDING);

        if (pendingRecords.isEmpty()) {
            return;
        }

        log.debug("Scanning {} pending settlement confirmations", pendingRecords.size());

        for (SettlementConfirmationRecord record : pendingRecords) {
            try {
                checkConfirmation(record.getPaymentId());

                // 如果仍然 PENDING，检查是否超时
                SettlementConfirmationRecord updated = repository.findByPaymentId(record.getPaymentId()).orElse(null);
                if (updated != null && updated.getStatus() == SettlementConfirmationStatus.PENDING) {
                    Duration elapsed = Duration.between(updated.getCreatedAt(), Instant.now());
                    if (elapsed.toMinutes() >= confirmationTimeoutMinutes) {
                        handleConfirmationTimeout(record.getPaymentId());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Error scanning confirmation for paymentId={}: {}",
                        record.getPaymentId(), e.getMessage());
            }
        }
    }

    /**
     * 手动触发重试（供 REST API 调用）。
     *
     * <p>将重试计数器加 1 并重新查询链上状态。
     * 如果重试次数超过上限，标记为 FAILED。</p>
     *
     * @param paymentId 支付 ID
     * @return 更新后的确认记录
     */
    @Transactional
    public SettlementConfirmationRecord manualRetry(String paymentId) {
        SettlementConfirmationRecord record = repository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("No confirmation record found for paymentId=" + paymentId));

        if (record.getStatus() == SettlementConfirmationStatus.CONFIRMED) {
            log.info("Confirmation already CONFIRMED for paymentId={}, no retry needed", paymentId);
            return record;
        }

        if (record.getStatus() == SettlementConfirmationStatus.FAILED) {
            // FAILED 状态允许手动重试，重置状态为 PENDING
            record.setStatus(SettlementConfirmationStatus.PENDING);
            record.setErrorMessage(null);
            log.info("Manual retry: resetting FAILED record to PENDING for paymentId={}", paymentId);
        }

        record.setRetryCount(record.getRetryCount() + 1);
        record.setLastCheckedAt(Instant.now());

        try {
            FinalityService.FinalityInfo finalityInfo = finalityService.getFinality(record.getTxHash());
            long confirmations = finalityInfo.confirmations();
            record.setConfirmations(confirmations);

            if (finalityInfo.status() == FinalityStatus.FINALIZED) {
                record.setStatus(SettlementConfirmationStatus.CONFIRMED);
                record.setConfirmedAt(Instant.now());
                record.setErrorMessage(null);
                log.info("Settlement confirmed on manual retry: paymentId={}, confirmations={}",
                        paymentId, confirmations);
                updateFinalityStatus(paymentId, FinalityStatus.FINALIZED);
            } else if (record.getRetryCount() >= maxRetryCount) {
                record.setStatus(SettlementConfirmationStatus.FAILED);
                record.setErrorMessage("Manual retry failed after " + record.getRetryCount() + " attempts");
                log.error("Manual retry FAILED: paymentId={}, retryCount={}", paymentId, record.getRetryCount());
            } else {
                log.info("Manual retry pending: paymentId={}, confirmations={}, finality={}",
                        paymentId, confirmations, finalityInfo.status());
            }
        } catch (RuntimeException e) {
            log.warn("Manual retry check failed for paymentId={}: {}", paymentId, e.getMessage());
            record.setErrorMessage("Manual retry failed: " + e.getMessage());
            if (record.getRetryCount() >= maxRetryCount) {
                record.setStatus(SettlementConfirmationStatus.FAILED);
            }
        }

        return repository.save(record);
    }

    /**
     * 查询确认记录（供 REST API 调用）。
     *
     * @param paymentId 支付 ID
     * @return 确认记录（Optional）
     */
    @Transactional(readOnly = true)
    public Optional<SettlementConfirmationRecord> getConfirmation(String paymentId) {
        return repository.findByPaymentId(paymentId);
    }

    /**
     * 分页查询确认记录（供 REST API 调用）。
     *
     * <p>P1-2 架构修复：原 Controller 直接调用 {@code repository.findByStatus/findAll}，
     * 现下沉到本服务，Controller 不再依赖 Repository。</p>
     *
     * @param status   可选状态过滤；为 null 时查询全部
     * @param pageable 分页参数
     * @return 确认记录分页结果
     */
    @Transactional(readOnly = true)
    public Page<SettlementConfirmationRecord> listConfirmations(SettlementConfirmationStatus status,
                                                                Pageable pageable) {
        if (status != null) {
            return repository.findByStatus(status, pageable);
        }
        return repository.findAll(pageable);
    }

    /**
     * 判断指定支付 ID 是否已存在确认记录（供 REST API 调用）。
     *
     * <p>P1-2 架构修复：原 Controller 直接调用 {@code repository.existsByPaymentId}，
     * 现下沉到本服务。</p>
     *
     * @param paymentId 支付 ID
     * @return true 表示已存在
     */
    @Transactional(readOnly = true)
    public boolean existsByPaymentId(String paymentId) {
        return repository.existsByPaymentId(paymentId);
    }

    /**
     * 检查记录是否超时（内部辅助方法）。
     */
    private void checkTimeout(SettlementConfirmationRecord record) {
        Duration elapsed = Duration.between(record.getCreatedAt(), Instant.now());
        if (elapsed.toMinutes() >= confirmationTimeoutMinutes) {
            record.setStatus(SettlementConfirmationStatus.TIMED_OUT);
            record.setRetryCount(record.getRetryCount() + 1);
            record.setErrorMessage("Confirmation timed out after " + elapsed.toMinutes() + " minutes");

            log.warn("Settlement confirmation timed out: paymentId={}, retryCount={}",
                    record.getPaymentId(), record.getRetryCount());

            if (record.getRetryCount() >= maxRetryCount) {
                record.setStatus(SettlementConfirmationStatus.FAILED);
                record.setErrorMessage("Confirmation failed after " + record.getRetryCount() + " retries");
                log.error("Settlement confirmation FAILED: paymentId={}", record.getPaymentId());
            }
        }
    }
}
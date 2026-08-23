package org.nexus.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.bridge.entity.IdempotencyKey;
import org.nexus.bridge.model.BridgeEvent;
import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.model.BridgeTransaction.BridgeOperationType;
import org.nexus.bridge.model.BridgeTransaction.BridgeTxStatus;
import org.nexus.bridge.repository.BridgeTransactionRepository;
import org.nexus.bridge.repository.IdempotencyKeyRepository;
import org.nexus.bridge.safety.CircuitBreaker;
import org.nexus.common.tracing.BusinessSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.tracing.Tracer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 桥服务实现：跨链锁定 / 铸造 / 销毁 / 解锁。
 *
 * <p>P3-T5：在桥跨链全链路添加业务 span（bridge.lock / bridge.mint /
 * bridge.burn / bridge.unlock），span 树结构见 docs/tracing-business-span.md。</p>
 */
@Service
public class BridgeServiceImpl implements BridgeService {

    private static final Logger log = LoggerFactory.getLogger(BridgeServiceImpl.class);

    private final BridgeConfig config;
    private final BridgeTransactionRepository txRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformTransactionManager transactionManager;
    /** Micrometer Tracer：P3-T5 业务 span 注入。可为 null（测试环境降级 no-op）。 */
    private final Tracer tracer;
    private final AtomicReference<BridgeState> bridgeState = new AtomicReference<>(BridgeState.ACTIVE);
    private final AtomicLong dailyUsed = new AtomicLong(0);
    private volatile long dailyResetTime = System.currentTimeMillis();

    // === P2-F2 幂等性 ===
    /** 幂等键 Repository（可为 null，测试环境降级跳过幂等检查）。 */
    @Autowired(required = false)
    private IdempotencyKeyRepository idempotencyKeyRepository;

    /** JSON 序列化器（可为 null，测试环境降级跳过幂等检查）。 */
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    /**
     * 熔断器（B-21 修复）：桥操作主流程接入熔断器。
     *
     * <p>通过 {@code required = false} 注入：未配置时为 {@code null}，
     * 桥操作不经过熔断检查（向后兼容）；注入后在 lock/mint/burn/unlock
     * 入口调用 {@link CircuitBreaker#acquirePermission()}，
     * 成功调用 {@link CircuitBreaker#recordSuccess()}，
     * 失败调用 {@link CircuitBreaker#recordFailure(String)}。</p>
     */
    @Autowired(required = false)
    private CircuitBreaker circuitBreaker;

    /** 幂等键有效期：24 小时。 */
    private static final long IDEMPOTENCY_TTL_SECONDS = 86_400L;

    /** 幂等操作类型常量。 */
    private static final String OP_LOCK = "LOCK";
    private static final String OP_MINT = "MINT";
    private static final String OP_BURN = "BURN";
    private static final String OP_UNLOCK = "UNLOCK";

    /**
     * 供单元测试使用的简化构造器（不注入事件发布器与事务管理器）。
     *
     * <p>无事务管理器时 {@link #fail(BridgeTransaction, String)} 退化为
     * 原地置 FAILED 并保存，事件发布为空操作。</p>
     */
    public BridgeServiceImpl(BridgeConfig config, BridgeTransactionRepository txRepository) {
        this(config, txRepository, null, null, null);
    }

    /**
     * Spring 注入使用的完整构造器。
     *
     * @param eventPublisher    桥事件发布器（可为 {@code null}）
     * @param transactionManager 事务管理器，用于以 REQUIRES_NEW 持久化 FAILED 状态
     * @param tracer            Micrometer Tracer（可为 {@code null}，测试降级 no-op）
     */
    @Autowired
    public BridgeServiceImpl(BridgeConfig config, BridgeTransactionRepository txRepository,
                             ApplicationEventPublisher eventPublisher,
                             PlatformTransactionManager transactionManager,
                             Tracer tracer) {
        this.config = config;
        this.txRepository = txRepository;
        this.eventPublisher = eventPublisher;
        this.transactionManager = transactionManager;
        this.tracer = tracer;
    }

    /**
     * 向后兼容构造器：不注入 Tracer，业务 span 降级为 no-op。
     */
    public BridgeServiceImpl(BridgeConfig config, BridgeTransactionRepository txRepository,
                             ApplicationEventPublisher eventPublisher,
                             PlatformTransactionManager transactionManager) {
        this(config, txRepository, eventPublisher, transactionManager, null);
    }


    @Override
    @Transactional
    public BridgeTransaction lock(LockRequest request) {
        // P3-T5：桥锁定 span（bridge.lock）
        try (BusinessSpan span = BusinessSpan.start(tracer, "bridge.lock")
                .attr("bridge.source.chain", request.getSourceChainId())
                .attr("bridge.target.chain", request.getTargetChainId())
                .attr("bridge.lock.amount", request.getAmount())
                .attr("bridge.user.address", request.getUserAddress())) {
            try {
                // B-21 修复：熔断器许可检查
                if (!acquireCircuitBreakerPermission("LOCK")) {
                    throw new BridgeException("Bridge circuit breaker is OPEN, LOCK rejected");
                }

                // P2-F2：幂等检查（在状态校验前短路返回之前结果）
                String idempotencyKey = request.getSourceTxHash();
                Optional<BridgeTransaction> existing = checkIdempotency(idempotencyKey, OP_LOCK);
                if (existing.isPresent()) {
                    span.attr("bridge.idempotent", true)
                            .attr("bridge.tx.id", existing.get().getTxId());
                    span.success();
                    recordCircuitBreakerSuccess();
                    return existing.get();
                }

                requireState(BridgeState.ACTIVE, "LOCK requires ACTIVE bridge");
                validateAmount(request.getAmount());

                // P1-F2：sourceTxHash 去重，防止中继者重放同一笔源链 lock 导致双倍 mint
                if (txRepository.findBySourceTxHash(request.getSourceTxHash()).isPresent()) {
                    log.warn("Duplicate source tx hash on LOCK: {}, skipping", request.getSourceTxHash());
                    throw new DuplicateTransactionException(
                            "Source tx already processed: " + request.getSourceTxHash());
                }

                BridgeTransaction tx = new BridgeTransaction();
                tx.setTxId(UUID.randomUUID().toString());
                tx.setOperationType(BridgeOperationType.BRIDGE_LOCK);
                tx.setSourceChainId(request.getSourceChainId());
                tx.setTargetChainId(request.getTargetChainId());
                tx.setAmount(request.getAmount());
                tx.setUserAddress(request.getUserAddress());
                tx.setTargetAddress(request.getTargetAddress());
                tx.setSourceTxHash(request.getSourceTxHash());
                tx.setValidatorIds(new HashSet<>());
                tx.setCreatedAt(Instant.now());
                tx.setUpdatedAt(Instant.now());
                tx.setMemo(request.getMemo());

                if (config.isLargeAmount(request.getAmount())) {
                    tx.setStatus(BridgeTxStatus.LOCK_PENDING);
                    tx.setTimelockExpiresAt(Instant.now().plusSeconds(config.getTimelockPeriodSeconds()));
                    span.attr("bridge.timelock", true);
                } else {
                    tx.setStatus(BridgeTxStatus.LOCKED);
                }

                span.attr("bridge.tx.id", tx.getTxId())
                        .attr("bridge.status", tx.getStatus().name());

                BridgeTransaction saved = txRepository.save(tx);
                publishTxEvent(saved, BridgeEvent.EventType.LOCK_CONFIRMED, "Lock confirmed");
                // P2-F2：保存幂等键
                saveIdempotencyKey(idempotencyKey, OP_LOCK, saved);
                span.success();
                recordCircuitBreakerSuccess();
                return saved;
            } catch (RuntimeException e) {
                span.error(e);
                recordCircuitBreakerFailure("LOCK", e);
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public BridgeTransaction mint(MintRequest request) {
        // P3-T5：桥铸造 span（bridge.mint）
        try (BusinessSpan span = BusinessSpan.start(tracer, "bridge.mint")
                .attr("bridge.lock.tx.id", request.getLockTxId())
                .attr("bridge.signatures.count", request.getSignatures() != null ? request.getSignatures().size() : 0)) {
            try {
                // B-21 修复：熔断器许可检查
                if (!acquireCircuitBreakerPermission("MINT")) {
                    throw new BridgeException("Bridge circuit breaker is OPEN, MINT rejected");
                }

                // P2-F2：幂等检查（以 lockTxId 为幂等键）
                Optional<BridgeTransaction> existing = checkIdempotency(request.getLockTxId(), OP_MINT);
                if (existing.isPresent()) {
                    span.attr("bridge.idempotent", true)
                            .attr("bridge.tx.id", existing.get().getTxId());
                    span.success();
                    recordCircuitBreakerSuccess();
                    return existing.get();
                }

                requireState(BridgeState.ACTIVE, "MINT requires ACTIVE bridge");

                BridgeTransaction lockTx = txRepository.findById(request.getLockTxId())
                        .orElseThrow(() -> new BridgeException("Lock transaction not found: " + request.getLockTxId()));
                span.attr("bridge.tx.id", lockTx.getTxId())
                        .attr("bridge.target.chain", lockTx.getTargetChainId())
                        .attr("bridge.mint.amount", lockTx.getAmount());
                if (lockTx.getStatus() != BridgeTxStatus.LOCKED) {
                    fail(lockTx, "Lock tx not in LOCKED state, current: " + lockTx.getStatus());
                    throw new BridgeException("Lock tx not in LOCKED state, current: " + lockTx.getStatus());
                }
                if (lockTx.getTimelockExpiresAt() != null && Instant.now().isBefore(lockTx.getTimelockExpiresAt())) {
                    fail(lockTx, "Timelock not yet expired, wait until: " + lockTx.getTimelockExpiresAt());
                    throw new BridgeException("Timelock not yet expired, wait until: " + lockTx.getTimelockExpiresAt());
                }

                // 1) 验签：签名者必须位于白名单且签名对确定性载荷有效
                Map<String, String> signatures = BridgeValidator.snapshot(request.getSignatures());
                String payload = mintPayload(lockTx, request);
                Set<String> validSigners = BridgeValidator.filterValidSignatures(payload, signatures, config);

                // 2) 数阈值：只对验签通过的签名计数
                if (!BridgeValidator.meetsThreshold(validSigners, config)) {
                    String reason = "Insufficient valid signatures: " + validSigners.size() + "/"
                            + config.getSignatureThreshold() + " (submitted: " + signatures.size() + ")";
                    fail(lockTx, reason);
                    span.attr("bridge.valid.signers", validSigners.size())
                            .attr("bridge.threshold", config.getSignatureThreshold())
                            .error(null);
                    throw new BridgeException(reason);
                }

                span.attr("bridge.valid.signers", validSigners.size());

                lockTx.setStatus(BridgeTxStatus.MINTED);
                lockTx.setValidatorIds(new HashSet<>(validSigners));
                lockTx.setUpdatedAt(Instant.now());
                BridgeTransaction saved = txRepository.save(lockTx);
                publishTxEvent(saved, BridgeEvent.EventType.MINT_CONFIRMED, "Mint confirmed");
                // P2-F2：保存幂等键
                saveIdempotencyKey(request.getLockTxId(), OP_MINT, saved);
                span.attr("bridge.status", "MINTED").success();
                recordCircuitBreakerSuccess();
                return saved;
            } catch (RuntimeException e) {
                span.error(e);
                recordCircuitBreakerFailure("MINT", e);
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public BridgeTransaction burn(BurnRequest request) {
        // P3-T5：桥销毁 span（bridge.burn）
        try (BusinessSpan span = BusinessSpan.start(tracer, "bridge.burn")
                .attr("bridge.source.chain", request.getSourceChainId())
                .attr("bridge.target.chain", request.getTargetChainId())
                .attr("bridge.burn.amount", request.getAmount())
                .attr("bridge.user.address", request.getUserAddress())) {
            try {
                // B-21 修复：熔断器许可检查
                if (!acquireCircuitBreakerPermission("BURN")) {
                    throw new BridgeException("Bridge circuit breaker is OPEN, BURN rejected");
                }

                // P2-F2：幂等检查
                String idempotencyKey = request.getSourceTxHash();
                Optional<BridgeTransaction> existing = checkIdempotency(idempotencyKey, OP_BURN);
                if (existing.isPresent()) {
                    span.attr("bridge.idempotent", true)
                            .attr("bridge.tx.id", existing.get().getTxId());
                    span.success();
                    recordCircuitBreakerSuccess();
                    return existing.get();
                }

                if (bridgeState.get() == BridgeState.EMERGENCY_STOP)
                    throw new BridgeException("Bridge is EMERGENCY_STOP, all operations forbidden");
                validateAmount(request.getAmount());

                // P1-F2：sourceTxHash 去重，防止中继者重放同一笔源链 burn 导致双倍 unlock
                if (txRepository.findBySourceTxHash(request.getSourceTxHash()).isPresent()) {
                    log.warn("Duplicate source tx hash on BURN: {}, skipping", request.getSourceTxHash());
                    throw new DuplicateTransactionException(
                            "Source tx already processed: " + request.getSourceTxHash());
                }

                BridgeTransaction tx = new BridgeTransaction();
                tx.setTxId(UUID.randomUUID().toString());
                tx.setOperationType(BridgeOperationType.BRIDGE_BURN);
                tx.setSourceChainId(request.getSourceChainId());
                tx.setTargetChainId(request.getTargetChainId());
                tx.setAmount(request.getAmount());
                tx.setUserAddress(request.getUserAddress());
                tx.setTargetAddress(request.getTargetAddress());
                tx.setSourceTxHash(request.getSourceTxHash());
                tx.setValidatorIds(new HashSet<>());
                tx.setStatus(BridgeTxStatus.BURNED);
                tx.setCreatedAt(Instant.now());
                tx.setUpdatedAt(Instant.now());
                span.attr("bridge.tx.id", tx.getTxId())
                        .attr("bridge.status", "BURNED");
                BridgeTransaction saved = txRepository.save(tx);
                publishTxEvent(saved, BridgeEvent.EventType.BURN_CONFIRMED, "Burn confirmed");
                // P2-F2：保存幂等键
                saveIdempotencyKey(idempotencyKey, OP_BURN, saved);
                span.success();
                recordCircuitBreakerSuccess();
                return saved;
            } catch (RuntimeException e) {
                span.error(e);
                recordCircuitBreakerFailure("BURN", e);
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public BridgeTransaction unlock(UnlockRequest request) {
        // P3-T5：桥解锁 span（bridge.unlock）
        try (BusinessSpan span = BusinessSpan.start(tracer, "bridge.unlock")
                .attr("bridge.burn.tx.id", request.getBurnTxId())
                .attr("bridge.signatures.count", request.getSignatures() != null ? request.getSignatures().size() : 0)) {
            try {
                // B-21 修复：熔断器许可检查
                if (!acquireCircuitBreakerPermission("UNLOCK")) {
                    throw new BridgeException("Bridge circuit breaker is OPEN, UNLOCK rejected");
                }

                // P2-F2：幂等检查（以 burnTxId 为幂等键）
                Optional<BridgeTransaction> existing = checkIdempotency(request.getBurnTxId(), OP_UNLOCK);
                if (existing.isPresent()) {
                    span.attr("bridge.idempotent", true)
                            .attr("bridge.tx.id", existing.get().getTxId());
                    span.success();
                    recordCircuitBreakerSuccess();
                    return existing.get();
                }

                if (bridgeState.get() == BridgeState.EMERGENCY_STOP)
                    throw new BridgeException("Bridge is EMERGENCY_STOP, all operations forbidden");

                BridgeTransaction burnTx = txRepository.findById(request.getBurnTxId())
                        .orElseThrow(() -> new BridgeException("Burn transaction not found: " + request.getBurnTxId()));
                span.attr("bridge.tx.id", burnTx.getTxId())
                        .attr("bridge.source.chain", burnTx.getSourceChainId())
                        .attr("bridge.unlock.amount", burnTx.getAmount());
                if (burnTx.getStatus() != BridgeTxStatus.BURNED) {
                    fail(burnTx, "Burn tx not in BURNED state, current: " + burnTx.getStatus());
                    throw new BridgeException("Burn tx not in BURNED state, current: " + burnTx.getStatus());
                }

                // 1) 验签：签名者必须位于白名单且签名对确定性载荷有效
                Map<String, String> signatures = BridgeValidator.snapshot(request.getSignatures());
                String payload = unlockPayload(burnTx, request);
                Set<String> validSigners = BridgeValidator.filterValidSignatures(payload, signatures, config);

                // 2) 数阈值：只对验签通过的签名计数
                if (!BridgeValidator.meetsThreshold(validSigners, config)) {
                    String reason = "Insufficient valid signatures: " + validSigners.size() + "/"
                            + config.getSignatureThreshold() + " (submitted: " + signatures.size() + ")";
                    fail(burnTx, reason);
                    span.attr("bridge.valid.signers", validSigners.size())
                            .attr("bridge.threshold", config.getSignatureThreshold())
                            .error(null);
                    throw new BridgeException(reason);
                }

                span.attr("bridge.valid.signers", validSigners.size());

                burnTx.setStatus(BridgeTxStatus.UNLOCKED);
                burnTx.setValidatorIds(new HashSet<>(validSigners));
                burnTx.setUpdatedAt(Instant.now());
                BridgeTransaction saved = txRepository.save(burnTx);
                publishTxEvent(saved, BridgeEvent.EventType.UNLOCK_CONFIRMED, "Unlock confirmed");
                // P2-F2：保存幂等键
                saveIdempotencyKey(request.getBurnTxId(), OP_UNLOCK, saved);
                span.attr("bridge.status", "UNLOCKED").success();
                recordCircuitBreakerSuccess();
                return saved;
            } catch (RuntimeException e) {
                span.error(e);
                recordCircuitBreakerFailure("UNLOCK", e);
                throw e;
            }
        }
    }

    @Override
    public BridgeTransaction getTransaction(String txId) {
        return txRepository.findById(txId).orElse(null);
    }

    @Override
    public BridgeTransaction getTransactionBySourceHash(String sourceTxHash) {
        return txRepository.findBySourceTxHash(sourceTxHash).orElse(null);
    }

    @Override
    public BridgeStatus getStatus() {
        resetDailyIfNeeded();
        BridgeStatus status = new BridgeStatus();
        status.setState(bridgeState.get());
        status.setDailyLimit(config.getDailyLimit());
        status.setDailyUsed(dailyUsed.get());
        status.setSignatureThreshold(config.getSignatureThreshold());
        status.setActiveValidatorCount(config.getValidatorPublicKeys() != null ? config.getValidatorPublicKeys().size() : 0);
        status.setPendingTxCount((int) txRepository.countByStatusIn(Arrays.asList(
                BridgeTxStatus.LOCK_PENDING, BridgeTxStatus.LOCKED, BridgeTxStatus.MINT_PENDING,
                BridgeTxStatus.BURN_PENDING, BridgeTxStatus.BURNED, BridgeTxStatus.UNLOCK_PENDING)));
        return status;
    }

    @Override
    public void pause(String validatorId) {
        if (bridgeState.get() == BridgeState.EMERGENCY_STOP)
            throw new BridgeException("Cannot pause: bridge is EMERGENCY_STOP");
        bridgeState.set(BridgeState.PAUSED);
        publishStateEvent(BridgeEvent.EventType.BRIDGE_PAUSED, "Bridge paused by " + validatorId, validatorId);
    }

    @Override
    public void resume(Set<String> validatorIds) {
        if (bridgeState.get() != BridgeState.PAUSED)
            throw new BridgeException("Cannot resume: bridge is not PAUSED");
        if (!BridgeValidator.meetsThreshold(validatorIds, config))
            throw new BridgeException("Insufficient signatures to resume");
        bridgeState.set(BridgeState.ACTIVE);
        publishStateEvent(BridgeEvent.EventType.BRIDGE_RESUMED,
                "Bridge resumed by " + validatorIds.size() + " validators", null);
    }

    /**
     * 将桥交易置为 FAILED 终态并记录失败原因（修复点 2）。
     *
     * <p>以 REQUIRES_NEW 独立事务持久化，确保外层调用事务回滚时
     * FAILED 状态仍然生效。终态（含已 FAILED）交易不会被二次改写。
     * 置 FAILED 后同时发布 {@code TRANSACTION_FAILED} 事件。</p>
     *
     * @param tx     待标记的交易（必须非终态，否则为 no-op）
     * @param reason 失败原因（写入 failureReason）
     * @return 保存后的交易（无事务管理器时可能为 {@code null}）
     */
    private BridgeTransaction fail(BridgeTransaction tx, String reason) {
        if (tx == null || tx.isTerminal()) {
            return tx;
        }
        final String txId = tx.getTxId();
        if (transactionManager == null) {
            // 测试/无事务基础设施场景：原地置 FAILED 并保存
            tx.setStatus(BridgeTxStatus.FAILED);
            tx.setFailureReason(reason);
            tx.setUpdatedAt(Instant.now());
            publishTxEvent(tx, BridgeEvent.EventType.TRANSACTION_FAILED, reason);
            return txRepository.save(tx);
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return template.execute(status -> {
                BridgeTransaction current = txRepository.findById(txId).orElse(tx);
                if (current.isTerminal()) {
                    return current;
                }
                current.setStatus(BridgeTxStatus.FAILED);
                current.setFailureReason(reason);
                current.setUpdatedAt(Instant.now());
                BridgeTransaction saved = txRepository.save(current);
                publishTxEvent(saved, BridgeEvent.EventType.TRANSACTION_FAILED, reason);
                return saved;
            });
        } catch (RuntimeException e) {
            // 独立事务失败不应吞掉原始业务异常；记录后返回原对象
            log.warn("Failed to persist FAILED status for tx {}: {}", txId, e.getMessage());
            tx.setStatus(BridgeTxStatus.FAILED);
            tx.setFailureReason(reason);
            return tx;
        }
    }

    /**
     * 构造 MINT 操作的签名载荷：确认的链上事件为源链 LOCK。
     *
     * @return {@link BridgeValidator#buildPayload} 生成的载荷哈希
     */
    private static String mintPayload(BridgeTransaction lockTx, MintRequest request) {
        return BridgeValidator.buildPayload(lockTx.getSourceChainId(), lockTx.getTxId(),
                lockTx.getAmount(), lockTx.getTargetAddress(), request.getTimestamp());
    }

    /**
     * 构造 UNLOCK 操作的签名载荷：确认的链上事件为目标链 BURN。
     *
     * @return {@link BridgeValidator#buildPayload} 生成的载荷哈希
     */
    private static String unlockPayload(BridgeTransaction burnTx, UnlockRequest request) {
        return BridgeValidator.buildPayload(burnTx.getTargetChainId(), burnTx.getTxId(),
                burnTx.getAmount(), burnTx.getTargetAddress(), request.getTimestamp());
    }

    /**
     * 发布与桥交易关联的事件（修复点 3）。
     */
    private void publishTxEvent(BridgeTransaction tx, BridgeEvent.EventType type, String description) {
        if (eventPublisher == null || tx == null) {
            return;
        }
        BridgeEvent event = new BridgeEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setTxId(tx.getTxId());
        event.setEventType(type);
        event.setSourceChainId(tx.getSourceChainId());
        event.setTargetChainId(tx.getTargetChainId());
        event.setAmount(tx.getAmount());
        event.setActor(tx.getUserAddress());
        event.setDescription(description);
        event.setTimestamp(Instant.now());
        eventPublisher.publishEvent(event);
    }

    /**
     * 发布桥状态类事件（无关联交易）。
     */
    private void publishStateEvent(BridgeEvent.EventType type, String description, String actor) {
        if (eventPublisher == null) {
            return;
        }
        BridgeEvent event = new BridgeEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(type);
        event.setSourceChainId(config.getSourceChainId());
        event.setTargetChainId(config.getTargetChainId());
        event.setActor(actor);
        event.setDescription(description);
        event.setTimestamp(Instant.now());
        eventPublisher.publishEvent(event);
    }

    private void requireState(BridgeState required, String msg) {
        if (bridgeState.get() != required) throw new BridgeException(msg + " (current: " + bridgeState.get() + ")");
    }

    // ==================== P2-F2 幂等性辅助 ====================

    /**
     * 幂等检查：按 (key, operation) 查询已存在的有效记录。
     *
     * <p>命中且未过期 → 反序列化返回之前结果；命中但已过期 → 视为未命中；
     * 未注入 Repository（测试环境）→ 视为未命中。</p>
     *
     * @param key       幂等键（可为 null，此时直接返回 empty）
     * @param operation 操作类型
     * @return 命中返回之前的结果，未命中返回 empty
     */
    private Optional<BridgeTransaction> checkIdempotency(String key, String operation) {
        if (idempotencyKeyRepository == null || objectMapper == null || key == null || key.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByKeyAndOperation(key, operation);
            if (existing.isEmpty() || existing.get().isExpired()) {
                return Optional.empty();
            }
            BridgeTransaction prior = objectMapper.readValue(existing.get().getResult(),
                    BridgeTransaction.class);
            log.info("Idempotent hit: key={}, operation={}, txId={}", key, operation, prior.getTxId());
            return Optional.of(prior);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Idempotency check failed (degrading to non-idempotent path): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 保存幂等键（操作成功后调用）。
     *
     * <p>失败仅记录日志，不影响主流程结果（幂等键是优化项，非正确性项）。</p>
     *
     * @param key       幂等键
     * @param operation 操作类型
     * @param result    操作结果
     */
    private void saveIdempotencyKey(String key, String operation, BridgeTransaction result) {
        if (idempotencyKeyRepository == null || objectMapper == null || key == null || key.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            Instant now = Instant.now();
            IdempotencyKey record = new IdempotencyKey(key, operation, json,
                    now.plusSeconds(IDEMPOTENCY_TTL_SECONDS));
            idempotencyKeyRepository.save(record);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Failed to persist idempotency key (key={}, op={}): {}",
                    key, operation, e.getMessage());
        }
    }

    // ==================== B-21 熔断器辅助 ====================

    /**
     * 获取熔断器执行许可（B-21 修复）。
     *
     * <p>未注入熔断器时返回 {@code true}（向后兼容）；注入后调用
     * {@link CircuitBreaker#acquirePermission()}。</p>
     *
     * @param operation 操作名称（LOCK/MINT/BURN/UNLOCK），用于日志
     * @return 允许执行返回 {@code true}；熔断中返回 {@code false}
     */
    private boolean acquireCircuitBreakerPermission(String operation) {
        if (circuitBreaker == null) {
            return true;
        }
        boolean permitted = circuitBreaker.acquirePermission();
        if (!permitted) {
            log.warn("Bridge {} rejected by circuit breaker: reason={}", operation, circuitBreaker.getTripReason());
        }
        return permitted;
    }

    /**
     * 记录桥操作成功（B-21 修复）。
     */
    private void recordCircuitBreakerSuccess() {
        if (circuitBreaker != null) {
            circuitBreaker.recordSuccess();
        }
    }

    /**
     * 记录桥操作失败（B-21 修复）。
     *
     * @param operation 操作名称
     * @param e         失败异常
     */
    private void recordCircuitBreakerFailure(String operation, RuntimeException e) {
        if (circuitBreaker != null) {
            circuitBreaker.recordFailure(operation + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 金额合法性校验 + 日限额配额预留（修复点 4）。
     *
     * <p>配额在事务内 CAS 预留（保持 TOCTOU 防护），并通过
     * {@link TransactionSynchronization#afterCompletion(int)} 在事务回滚时
     * 归还配额，避免"事务回滚但 AtomicLong 不回滚"的额度泄漏。</p>
     */
    private void validateAmount(long amount) {
        if (amount <= 0) throw new BridgeException("Amount must be positive");
        if (config.exceedsMaxAmount(amount)) throw new BridgeException("Amount exceeds single tx limit");
        reserveDailyQuota(amount);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        releaseDailyQuota(amount);
                        log.warn("Transaction rolled back; released reserved daily quota of {} (dailyUsed={})",
                                amount, dailyUsed.get());
                    }
                }
            });
        }
    }

    /**
     * 原子预留日限额配额（CAS 循环，防 TOCTOU 竞态）。
     */
    private void reserveDailyQuota(long amount) {
        resetDailyIfNeeded();
        while (true) {
            long current = dailyUsed.get();
            if (config.exceedsDailyLimit(amount, current)) throw new BridgeException("Daily limit exceeded");
            if (dailyUsed.compareAndSet(current, current + amount)) break;
        }
    }

    /**
     * 归还此前预留的日限额配额（回滚补偿），下限钳制为 0。
     */
    private void releaseDailyQuota(long amount) {
        dailyUsed.updateAndGet(v -> Math.max(0, v - amount));
    }

    private void resetDailyIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - dailyResetTime > 86_400_000L) { dailyUsed.set(0); dailyResetTime = now; }
    }
}

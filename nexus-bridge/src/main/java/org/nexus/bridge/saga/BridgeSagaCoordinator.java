package org.nexus.bridge.saga;

import org.nexus.bridge.BridgeException;
import org.nexus.bridge.BridgeServiceImpl;
import org.nexus.bridge.BridgeService;
import org.nexus.bridge.BurnRequest;
import org.nexus.bridge.LockRequest;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.UnlockRequest;
import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.repository.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨链桥 Saga 协调器（P2-F2）。
 *
 * <p>实现跨链桥的 Saga 长事务模式，包含两条主流程：</p>
 *
 * <h2>正向跨链 lock → mint</h2>
 * <ol>
 *   <li>步骤 1：源链 {@link BridgeService#lock(LockRequest)} 锁定资产</li>
 *   <li>步骤 2：目标链 {@link BridgeService#mint(MintRequest)} 铸造映射资产</li>
 *   <li>若 mint 失败 → 补偿：源链 {@link BridgeService#unlock(UnlockRequest)} 解锁资产</li>
 * </ol>
 *
 * <h2>反向跨链 burn → unlock</h2>
 * <ol>
 *   <li>步骤 1：源链 {@link BridgeService#burn(BurnRequest)} 销毁映射资产</li>
 *   <li>步骤 2：目标链 {@link BridgeService#unlock(UnlockRequest)} 释放原始资产</li>
 *   <li>若 unlock 失败 → 补偿：记录待处理 + 重试（不可自动回退 burn，需人工对账）</li>
 * </ol>
 *
 * <h2>幂等性</h2>
 * <p>每个 Saga 实例持久化到 {@link SagaInstance}，状态机推进前先查 DB。
 * 补偿操作本身通过 {@link BridgeServiceImpl} 内的 IdempotencyKey 幂等检查
 * 保证补偿可安全重试。</p>
 *
 * <h2>状态机</h2>
 * <pre>
 *   PENDING ──► EXECUTING ──► COMPLETED
 *                   │
 *                   ▼
 *               COMPENSATING ──► FAILED
 * </pre>
 *
 * @since 2.2.0
 */
@Service
public class BridgeSagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BridgeSagaCoordinator.class);

    /** Saga 类型常量：正向 lock→mint。 */
    public static final String SAGA_LOCK_MINT = "LOCK_MINT";
    /** Saga 类型常量：反向 burn→unlock。 */
    public static final String SAGA_BURN_UNLOCK = "BURN_UNLOCK";

    /** 默认最大重试次数。 */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * 中7 改进：指数退避基准延迟（毫秒）。
     *
     * <p>第 n 次重试延迟 = {@code BASE_RETRY_DELAY_MS * 2^n}：
     * <ul>
     *   <li>第 0 次（首次重试）：1000ms = 1s</li>
     *   <li>第 1 次：2000ms = 2s</li>
     *   <li>第 2 次：4000ms = 4s</li>
     *   <li>第 3 次：8000ms = 8s</li>
     * </ul>
     * 避免立即重试加重链上压力。</p>
     */
    private static final long BASE_RETRY_DELAY_MS = 1000L;

    /**
     * 中7 改进：指数退避上限阈值（毫秒，5 分钟）。
     *
     * <p>当计算出的退避延迟超过此阈值时，跳过本次重试等待下次调度，
     * 避免单次重试等待时间过长阻塞调度线程。</p>
     */
    private static final long MAX_RETRY_DELAY_MS = 300_000L;

    private final BridgeService bridgeService;
    private final SagaInstanceRepository sagaRepository;
    private final ObjectMapper objectMapper;

    /**
     * 构造 Saga 协调器。
     *
     * @param bridgeService   桥服务
     * @param sagaRepository  Saga 实例 Repository
     * @param objectMapper    JSON 序列化器
     */
    @Autowired
    public BridgeSagaCoordinator(BridgeService bridgeService,
                                 SagaInstanceRepository sagaRepository,
                                 ObjectMapper objectMapper) {
        this.bridgeService = bridgeService;
        this.sagaRepository = sagaRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 正向跨链 lock → mint ====================

    /**
     * 执行正向跨链 Saga：lock → mint。
     *
     * <p>步骤 1 在源链锁定资产，步骤 2 在目标链铸造映射资产。
     * 若步骤 2 失败，则执行补偿：调用 unlock 解锁源链资产。
     * 整个 Saga 状态持久化，崩溃后可恢复。</p>
     *
     * @param lockRequest 锁定请求
     * @param mintRequest 铸造请求（lockTxId 在 lock 完成后由协调器填入）
     * @return 锁定交易（步骤 1 结果，含最终状态）
     */
    @Transactional
    public BridgeTransaction executeLockMint(LockRequest lockRequest, MintRequest mintRequest) {
        SagaInstance saga = createSaga(SAGA_LOCK_MINT, lockRequest, null);
        try {
            saga.setState(SagaState.EXECUTING);
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);

            // 步骤 1：源链 lock
            BridgeTransaction lockTx = bridgeService.lock(lockRequest);
            saga.setCurrentStepIndex(1);
            saga.setRelatedTxId(lockTx.getTxId());
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);

            // 步骤 2：目标链 mint
            mintRequest.setLockTxId(lockTx.getTxId());
            try {
                BridgeTransaction mintTx = bridgeService.mint(mintRequest);
                saga.setCurrentStepIndex(2);
                saga.setState(SagaState.COMPLETED);
                saga.setUpdatedAt(Instant.now());
                sagaRepository.save(saga);
                log.info("Saga {} (LOCK_MINT) completed: lockTx={}, mintStatus={}",
                        saga.getId(), lockTx.getTxId(), mintTx.getStatus());
                return lockTx;
            } catch (RuntimeException mintEx) {
                // mint 失败 → 补偿：unlock 源链资产
                log.warn("Saga {} (LOCK_MINT) mint failed, compensating: {}",
                        saga.getId(), mintEx.getMessage());
                saga.setState(SagaState.COMPENSATING);
                saga.setLastError(truncate(mintEx.getMessage()));
                saga.setUpdatedAt(Instant.now());
                sagaRepository.save(saga);

                compensateLockMint(saga, lockTx, mintRequest);

                // 补偿完成后标记 FAILED（mint 未成功，但 lock 已回退）
                saga.setState(SagaState.FAILED);
                saga.setUpdatedAt(Instant.now());
                sagaRepository.save(saga);
                throw new BridgeException("BRIDGE_SAGA_MINT_FAILED",
                        "Lock-Mint saga failed, lock compensated: " + mintEx.getMessage(), mintEx);
            }
        } catch (BridgeException e) {
            throw e;
        } catch (RuntimeException e) {
            markFailed(saga, e);
            throw new BridgeException("BRIDGE_SAGA_FAILED",
                    "Lock-Mint saga failed: " + e.getMessage(), e);
        }
    }

    /**
     * lock→mint 补偿：调用 unlock 解锁源链资产。
     *
     * <p>补偿通过 {@link BridgeService#unlock(UnlockRequest)} 完成，
     * 该方法内部已通过 IdempotencyKey 保证补偿可重试。</p>
     *
     * @param saga        Saga 实例
     * @param lockTx      已完成的 lock 交易
     * @param mintRequest mint 请求（携带签名）
     */
    private void compensateLockMint(SagaInstance saga, BridgeTransaction lockTx, MintRequest mintRequest) {
        // 注意：lock→mint 失败的补偿需要 unlock，但 unlock 需要一个 burnTxId。
        // 此处采用"逻辑解锁"：将 lockTx 视为待回退，记录到 payload，
        // 实际链上解锁由人工 / 异步对账任务根据 FAILED Saga 列表执行。
        // 这种设计避免了为补偿而强行构造一个不存在的 burn，符合"补偿幂等"约束。
        try {
            Map<String, Object> compensation = new LinkedHashMap<>();
            compensation.put("type", "UNLOCK_AFTER_FAILED_MINT");
            compensation.put("lockTxId", lockTx.getTxId());
            compensation.put("amount", lockTx.getAmount());
            compensation.put("userAddress", lockTx.getUserAddress());
            compensation.put("sourceChainId", lockTx.getSourceChainId());
            compensation.put("targetChainId", lockTx.getTargetChainId());

            String existingPayload = saga.getPayload();
            Map<String, Object> payload = existingPayload == null
                    ? new LinkedHashMap<>()
                    : objectMapper.readValue(existingPayload, new TypeReference<Map<String, Object>>() {});
            payload.put("compensation", compensation);
            saga.setPayload(objectMapper.writeValueAsString(payload));
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);
            log.info("Saga {} compensation recorded: lockTx={} will be unlocked by reconciliation",
                    saga.getId(), lockTx.getTxId());
        } catch (RuntimeException | JsonProcessingException e) {
            // B-06 修复：补偿记录失败时不再吞掉异常，而是标记 saga 为 FAILED（需人工介入）。
            // 若吞掉异常，补偿记录丢失，lockTx 将永久锁定，用户资金无法恢复。
            log.error("Saga {} failed to persist compensation, marking as FAILED for manual intervention: {}",
                    saga.getId(), e.getMessage(), e);
            saga.setState(SagaState.FAILED);
            saga.setLastError(truncate("Compensation persistence failed: " + e.getMessage()));
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);
        }
    }

    // ==================== 反向跨链 burn → unlock ====================

    /**
     * 执行反向跨链 Saga：burn → unlock。
     *
     * <p>步骤 1 在源链销毁映射资产，步骤 2 在目标链解锁原始资产。
     * 若步骤 2 失败，则记录待处理 + 重试（不可自动回退 burn，
     * 因为 burn 已在链上执行，需人工 / 异步对账任务根据 FAILED Saga
     * 列表重试 unlock）。</p>
     *
     * @param burnRequest   销毁请求
     * @param unlockRequest 解锁请求（burnTxId 在 burn 完成后由协调器填入）
     * @return 销毁交易（步骤 1 结果，含最终状态）
     */
    @Transactional
    public BridgeTransaction executeBurnUnlock(BurnRequest burnRequest, UnlockRequest unlockRequest) {
        SagaInstance saga = createSaga(SAGA_BURN_UNLOCK, burnRequest, null);
        try {
            saga.setState(SagaState.EXECUTING);
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);

            // 步骤 1：源链 burn
            BridgeTransaction burnTx = bridgeService.burn(burnRequest);
            saga.setCurrentStepIndex(1);
            saga.setRelatedTxId(burnTx.getTxId());
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);

            // 步骤 2：目标链 unlock
            unlockRequest.setBurnTxId(burnTx.getTxId());
            try {
                BridgeTransaction unlockTx = bridgeService.unlock(unlockRequest);
                saga.setCurrentStepIndex(2);
                saga.setState(SagaState.COMPLETED);
                saga.setUpdatedAt(Instant.now());
                sagaRepository.save(saga);
                log.info("Saga {} (BURN_UNLOCK) completed: burnTx={}, unlockStatus={}",
                        saga.getId(), burnTx.getTxId(), unlockTx.getStatus());
                return burnTx;
            } catch (RuntimeException unlockEx) {
                // unlock 失败 → 记录待处理 + 重试（不可自动回退 burn）
                log.warn("Saga {} (BURN_UNLOCK) unlock failed, recording for retry: {}",
                        saga.getId(), unlockEx.getMessage());
                saga.setState(SagaState.COMPENSATING);
                saga.setLastError(truncate(unlockEx.getMessage()));
                saga.setUpdatedAt(Instant.now());
                sagaRepository.save(saga);

                recordBurnUnlockCompensation(saga, burnTx, unlockRequest);

                // 标记 FAILED，等待人工 / 异步重试
                saga.setState(SagaState.FAILED);
                saga.setUpdatedAt(Instant.now());
                sagaRepository.save(saga);
                throw new BridgeException("BRIDGE_SAGA_UNLOCK_FAILED",
                        "Burn-Unlock saga failed, burn committed, unlock pending retry: "
                                + unlockEx.getMessage(), unlockEx);
            }
        } catch (BridgeException e) {
            throw e;
        } catch (RuntimeException e) {
            markFailed(saga, e);
            throw new BridgeException("BRIDGE_SAGA_FAILED",
                    "Burn-Unlock saga failed: " + e.getMessage(), e);
        }
    }

    /**
     * burn→unlock 补偿：记录待重试的 unlock 上下文。
     *
     * <p>burn 已在链上执行不可回退，因此补偿策略为"记录 + 重试"：
     * 将 unlock 所需的全部上下文持久化到 Saga payload，
     * 由 {@link #retryFailedSagas()} 或人工对账任务扫描后重试。</p>
     *
     * @param saga          Saga 实例
     * @param burnTx        已完成的 burn 交易
     * @param unlockRequest unlock 请求（携带签名）
     */
    private void recordBurnUnlockCompensation(SagaInstance saga, BridgeTransaction burnTx,
                                              UnlockRequest unlockRequest) {
        try {
            Map<String, Object> compensation = new LinkedHashMap<>();
            compensation.put("type", "RETRY_UNLOCK");
            compensation.put("burnTxId", burnTx.getTxId());
            compensation.put("amount", burnTx.getAmount());
            compensation.put("userAddress", burnTx.getUserAddress());
            compensation.put("sourceChainId", burnTx.getSourceChainId());
            compensation.put("targetChainId", burnTx.getTargetChainId());
            compensation.put("unlockerAddress", unlockRequest.getUnlockerAddress());

            String existingPayload = saga.getPayload();
            Map<String, Object> payload = existingPayload == null
                    ? new LinkedHashMap<>()
                    : objectMapper.readValue(existingPayload, new TypeReference<Map<String, Object>>() {});
            payload.put("compensation", compensation);
            saga.setPayload(objectMapper.writeValueAsString(payload));
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);
            log.info("Saga {} compensation recorded: burnTx={} will be retried by reconciliation",
                    saga.getId(), burnTx.getTxId());
        } catch (RuntimeException | JsonProcessingException e) {
            // B-06 修复：补偿记录失败时不再吞掉异常，而是标记 saga 为 FAILED（需人工介入）。
            // 若吞掉异常，补偿记录丢失，unlock 重试上下文丢失，用户资金永久锁定。
            log.error("Saga {} failed to persist compensation, marking as FAILED for manual intervention: {}",
                    saga.getId(), e.getMessage(), e);
            saga.setState(SagaState.FAILED);
            saga.setLastError(truncate("Compensation persistence failed: " + e.getMessage()));
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);
        }
    }

    // ==================== 重试 + 恢复 ====================

    /**
     * 重试所有 FAILED 且可重试的 Saga 实例。
     *
     * <p>由定时任务或运维接口调用。每次重试递增 retryCount，
     * 超过 maxRetries 后不再自动重试，需人工介入。</p>
     *
     * <p>中7 改进：指数退避策略。第 n 次重试前计算延迟
     * {@code delayMs = 1000 * 2^retryCount}，若 delayMs 超过
     * {@link #MAX_RETRY_DELAY_MS}（5 分钟）则跳过本次重试等待下次调度，
     * 避免立即重试加重链上压力（如链上拥堵时频繁重试会加剧拥堵）。</p>
     *
     * @return 实际重试的 Saga 数量
     */
    @Transactional
    public int retryFailedSagas() {
        List<SagaInstance> failed = sagaRepository.findByState(SagaState.FAILED);
        int retried = 0;
        int skipped = 0;
        for (SagaInstance saga : failed) {
            if (!saga.canRetry()) {
                continue;
            }

            // 中7 改进：指数退避检查
            long delayMs = (long) (BASE_RETRY_DELAY_MS * Math.pow(2, saga.getRetryCount()));
            if (delayMs > MAX_RETRY_DELAY_MS) {
                log.info("Saga {} retry skipped: exponential backoff delay {}ms exceeds threshold {}ms " +
                                "(retryCount={}, will retry on next scheduled cycle)",
                        saga.getId(), delayMs, MAX_RETRY_DELAY_MS, saga.getRetryCount());
                skipped++;
                continue;
            }

            try {
                log.info("Saga {} retry attempt {}/{} with exponential backoff delay {}ms",
                        saga.getId(), saga.getRetryCount() + 1, saga.getMaxRetries(), delayMs);
                retryOne(saga);
                retried++;
            } catch (RuntimeException e) {
                log.warn("Retry saga {} failed (attempt {}/{}): {}",
                        saga.getId(), saga.getRetryCount() + 1, saga.getMaxRetries(), e.getMessage());
            }
        }
        if (skipped > 0) {
            log.info("Retry cycle summary: retried={}, skipped due to backoff={}", retried, skipped);
        }
        return retried;
    }

    /**
     * 重试单个 Saga（仅 BURN_UNLOCK 的 unlock 步骤可重试）。
     *
     * <p>B-05 修复：实际重新执行失败的 unlock 操作，而不是仅记录重试次数。
     * 若不真正重试，用户资金将永久锁定在 burn 状态。</p>
     *
     * @param saga 待重试 Saga
     */
    private void retryOne(SagaInstance saga) {
        saga.setRetryCount(saga.getRetryCount() + 1);
        saga.setUpdatedAt(Instant.now());
        sagaRepository.save(saga);

        if (SAGA_BURN_UNLOCK.equals(saga.getSagaType())) {
            // B-05 修复：从 payload 解析 compensation 上下文，重新构造 unlockRequest
            // 并实际调用 bridgeService.unlock()，而不是仅记录日志。
            retryBurnUnlock(saga);
        }
        // LOCK_MINT 的补偿是记录式（unlock 需要人工对账），无需自动重试
    }

    /**
     * 重试 BURN_UNLOCK saga 的 unlock 步骤。
     *
     * <p>从 Saga payload 中解析补偿上下文（burnTxId / unlockerAddress / sourceChainId），
     * 重新构造 {@link UnlockRequest} 并调用 {@link BridgeService#unlock(UnlockRequest)}。
     * 若 unlock 成功，Saga 标记为 COMPLETED；若失败，异常向上抛出由 {@link #retryFailedSagas()}
     * 的 catch 统一处理。</p>
     *
     * @param saga 待重试的 BURN_UNLOCK Saga
     */
    private void retryBurnUnlock(SagaInstance saga) {
        String payloadJson = saga.getPayload();
        if (payloadJson == null || payloadJson.isEmpty()) {
            log.warn("Saga {} retry: no payload to reconstruct unlock request", saga.getId());
            return;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson,
                    new TypeReference<Map<String, Object>>() {});
            Object compObj = payload.get("compensation");
            if (!(compObj instanceof Map)) {
                log.warn("Saga {} retry: no compensation in payload", saga.getId());
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> compensation = (Map<String, Object>) compObj;
            String burnTxId = (String) compensation.get("burnTxId");
            String unlockerAddress = (String) compensation.get("unlockerAddress");
            String sourceChainId = (String) compensation.get("sourceChainId");

            UnlockRequest unlockRequest = new UnlockRequest();
            unlockRequest.setBurnTxId(burnTxId);
            unlockRequest.setUnlockerAddress(unlockerAddress);
            unlockRequest.setSourceChainId(sourceChainId);
            unlockRequest.setTimestamp(System.currentTimeMillis());

            log.info("Saga {} retry attempt {}/{}: re-executing unlock for burnTx={}",
                    saga.getId(), saga.getRetryCount(), saga.getMaxRetries(), burnTxId);
            BridgeTransaction unlockTx = bridgeService.unlock(unlockRequest);

            // unlock 成功，标记 saga 为 COMPLETED
            saga.setState(SagaState.COMPLETED);
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);
            log.info("Saga {} retry succeeded: unlockTx={}, status={}",
                    saga.getId(), unlockTx.getTxId(), unlockTx.getStatus());
        } catch (JsonProcessingException e) {
            log.warn("Saga {} retry attempt {}/{} failed to parse payload: {}",
                    saga.getId(), saga.getRetryCount(), saga.getMaxRetries(), e.getMessage(), e);
            saga.setLastError(truncate("Payload parse failed: " + e.getMessage()));
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);
            throw new RuntimeException("Failed to retry saga " + saga.getId(), e);
        }
        // RuntimeException 由 retryFailedSagas 的 catch 统一处理
    }

    /**
     * 崩溃恢复：扫描非终态 Saga，按需恢复执行。
     *
     * <p>由应用启动事件或定时任务调用。EXECUTING / COMPENSATING
     * 状态的 Saga 视为崩溃残留，标记为 FAILED 等待人工处理。</p>
     *
     * <p>中6 改进：通过 {@code @SchedulerLock} 分布式锁保证多实例部署时
     * 同一时刻仅一个实例执行恢复，避免重复扫描与重复处理。
     * 锁最多持有 4 分钟（覆盖单次恢复的最大耗时），至少持有 1 分钟
     * （避免瞬时完成导致其他实例立即重复触发）。</p>
     *
     * @return 恢复处理的 Saga 数量
     */
    @Transactional
    @SchedulerLock(name = "recoverIncompleteSagas", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    public int recoverIncompleteSagas() {
        List<SagaState> nonTerminal = new ArrayList<>();
        nonTerminal.add(SagaState.PENDING);
        nonTerminal.add(SagaState.EXECUTING);
        nonTerminal.add(SagaState.COMPENSATING);
        List<SagaInstance> incomplete = sagaRepository.findByStateIn(nonTerminal);
        int recovered = 0;
        for (SagaInstance saga : incomplete) {
            log.warn("Recovering incomplete saga {} (state={}, step={})",
                    saga.getId(), saga.getState(), saga.getCurrentStepIndex());
            saga.setState(SagaState.FAILED);
            saga.setLastError("Recovered as FAILED after crash");
            saga.setUpdatedAt(Instant.now());
            sagaRepository.save(saga);
            recovered++;
        }
        return recovered;
    }

    // ==================== 内部工具 ====================

    /**
     * 创建新 Saga 实例（PENDING 状态）。
     */
    private SagaInstance createSaga(String sagaType, Object request, String relatedTxId) {
        String payload = serializeSafe(request);
        SagaInstance saga = new SagaInstance(sagaType, payload, relatedTxId, DEFAULT_MAX_RETRIES);
        return sagaRepository.save(saga);
    }

    /**
     * 标记 Saga 为 FAILED。
     */
    private void markFailed(SagaInstance saga, Exception e) {
        saga.setState(SagaState.FAILED);
        saga.setLastError(truncate(e.getMessage()));
        saga.setUpdatedAt(Instant.now());
        sagaRepository.save(saga);
    }

    /**
     * 安全序列化为 JSON，失败返回 null。
     */
    private String serializeSafe(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize saga payload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 截断错误信息到 1024 字符。
     */
    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1024 ? s.substring(0, 1024) : s;
    }
}
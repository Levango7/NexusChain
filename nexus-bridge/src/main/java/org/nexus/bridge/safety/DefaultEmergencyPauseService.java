package org.nexus.bridge.safety;

import org.nexus.bridge.model.BridgePauseRecord;
import org.nexus.bridge.repository.BridgePauseRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 紧急暂停服务默认实现。
 *
 * <p>基于内存 {@link ConcurrentHashMap} 缓存 + JPA 持久化双重保障：</p>
 * <ul>
 *   <li>内存缓存提供高频 {@link #isPaused(String)} 查询的 O(1) 访问</li>
 *   <li>JPA 持久化保证服务重启后状态可恢复</li>
 *   <li>所有写操作在 {@code @Transactional} 事务内执行，保证一致性</li>
 * </ul>
 *
 * <h2>状态语义</h2>
 * <ul>
 *   <li>{@code ACTIVE} — 桥正常运行，所有操作可用</li>
 *   <li>{@code PAUSED} — 桥暂停，仅允许 UNLOCK 退回资产</li>
 *   <li>{@code EMERGENCY_STOP} — 紧急停止，所有操作禁止</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>内存状态使用 {@link ConcurrentHashMap} 保证并发读；写操作通过
 * 事务串行化 + 内存原子 put 保证一致性。</p>
 *
 * @since 1.2
 */
@Service
public class DefaultEmergencyPauseService implements EmergencyPauseService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEmergencyPauseService.class);

    /** 状态常量：正常运行。 */
    public static final String STATE_ACTIVE = "ACTIVE";
    /** 状态常量：暂停。 */
    public static final String STATE_PAUSED = "PAUSED";
    /** 状态常量：紧急停止。 */
    public static final String STATE_EMERGENCY_STOP = "EMERGENCY_STOP";

    private final BridgePauseRecordRepository repository;

    /** 内存状态缓存：bridgeId → 状态字符串。 */
    private final ConcurrentHashMap<String, String> stateCache = new ConcurrentHashMap<>();

    /** 内存原因缓存：bridgeId → 暂停原因。 */
    private final ConcurrentHashMap<String, String> reasonCache = new ConcurrentHashMap<>();

    /**
     * 构造默认紧急暂停服务。
     *
     * <p>启动时从数据库加载所有已记录的桥状态到内存缓存。</p>
     *
     * @param repository 桥暂停记录 Repository
     */
    @Autowired
    public DefaultEmergencyPauseService(BridgePauseRecordRepository repository) {
        this.repository = repository;
        try {
            for (BridgePauseRecord record : repository.findAll()) {
                stateCache.put(record.getBridgeId(), record.getState());
                if (record.getReason() != null) {
                    reasonCache.put(record.getBridgeId(), record.getReason());
                }
            }
            log.info("Loaded {} bridge pause records from DB", stateCache.size());
        } catch (RuntimeException e) {
            log.warn("Failed to preload bridge pause records (DB may not be ready): {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void pauseBridge(String bridgeId) {
        triggerPause(bridgeId, STATE_PAUSED, "manual pause", null);
    }

    @Override
    @Transactional
    public void resumeBridge(String bridgeId) {
        if (bridgeId == null || bridgeId.isEmpty()) {
            throw new IllegalArgumentException("bridgeId must not be null or empty");
        }
        BridgePauseRecord record = repository.findById(bridgeId)
                .orElseGet(() -> new BridgePauseRecord(bridgeId, STATE_ACTIVE, null, null));
        record.setState(STATE_ACTIVE);
        record.setReason(null);
        record.setUpdatedAt(Instant.now());
        repository.save(record);

        stateCache.put(bridgeId, STATE_ACTIVE);
        reasonCache.remove(bridgeId);
        log.info("Bridge {} resumed to ACTIVE", bridgeId);
    }

    @Override
    public Map<String, String> getPossibleStatus() {
        return Collections.unmodifiableMap(stateCache);
    }

    // ==================== 扩展方法（任务说明要求） ====================

    /**
     * 触发桥暂停 / 紧急停止。
     *
     * @param bridgeId   桥 ID
     * @param state      目标状态（PAUSED 或 EMERGENCY_STOP）
     * @param reason     暂停原因
     * @param triggeredBy 触发者 ID（可为 null）
     */
    @Transactional
    public void triggerPause(String bridgeId, String state, String reason, String triggeredBy) {
        if (bridgeId == null || bridgeId.isEmpty()) {
            throw new IllegalArgumentException("bridgeId must not be null or empty");
        }
        if (!STATE_PAUSED.equals(state) && !STATE_EMERGENCY_STOP.equals(state)) {
            throw new IllegalArgumentException("Invalid pause state: " + state
                    + " (expected PAUSED or EMERGENCY_STOP)");
        }
        BridgePauseRecord record = repository.findById(bridgeId)
                .orElseGet(() -> new BridgePauseRecord(bridgeId, state, reason, triggeredBy));
        record.setState(state);
        record.setReason(reason);
        record.setTriggeredBy(triggeredBy);
        record.setUpdatedAt(Instant.now());
        repository.save(record);

        stateCache.put(bridgeId, state);
        if (reason != null) {
            reasonCache.put(bridgeId, reason);
        }
        log.warn("Bridge {} triggered to {} by {}: {}", bridgeId, state, triggeredBy, reason);
    }

    /**
     * 查询桥是否处于暂停 / 紧急停止状态。
     *
     * @param bridgeId 桥 ID
     * @return 暂停或紧停返回 true；ACTIVE 或未知返回 false
     */
    public boolean isPaused(String bridgeId) {
        String state = stateCache.get(bridgeId);
        return STATE_PAUSED.equals(state) || STATE_EMERGENCY_STOP.equals(state);
    }

    /**
     * 查询桥是否处于紧急停止状态。
     *
     * @param bridgeId 桥 ID
     * @return 紧停返回 true；否则返回 false
     */
    public boolean isEmergencyStopped(String bridgeId) {
        return STATE_EMERGENCY_STOP.equals(stateCache.get(bridgeId));
    }

    /**
     * 查询桥当前暂停原因。
     *
     * @param bridgeId 桥 ID
     * @return 暂停原因；未暂停或未知返回 null
     */
    public String getPauseReason(String bridgeId) {
        return reasonCache.get(bridgeId);
    }

    /**
     * 查询桥当前状态。
     *
     * @param bridgeId 桥 ID
     * @return 状态字符串（ACTIVE / PAUSED / EMERGENCY_STOP）；未知返回 ACTIVE
     */
    public String getBridgeState(String bridgeId) {
        return stateCache.getOrDefault(bridgeId, STATE_ACTIVE);
    }
}
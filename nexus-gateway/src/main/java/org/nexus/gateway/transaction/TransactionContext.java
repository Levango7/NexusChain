package org.nexus.gateway.transaction;

import java.util.Map;

/**
 * 分布式事务上下文。
 *
 * <p>贯穿 TCC/SAGA 事务的整个生命周期，携带事务标识、步骤名称、业务载荷
 * 和租户信息，在事务管理器与参与方之间传递。</p>
 *
 * <p>关键字段：</p>
 * <ul>
 *   <li>{@code transactionId} — 事务唯一标识（UUID），用于幂等去重</li>
 *   <li>{@code stepName} — 当前步骤名称</li>
 *   <li>{@code payload} — 业务数据（Map 形式，灵活携带各类业务参数）</li>
 *   <li>{@code tenantId} — 多租户隔离键</li>
 * </ul>
 */
public class TransactionContext {

    /** 事务唯一标识（UUID），用于幂等去重 */
    private final String transactionId;

    /** 当前步骤名称 */
    private final String stepName;

    /** 业务数据载荷 */
    private final Map<String, Object> payload;

    /** 多租户隔离键 */
    private final String tenantId;

    public TransactionContext(String transactionId, String stepName,
                              Map<String, Object> payload, String tenantId) {
        this.transactionId = transactionId;
        this.stepName = stepName;
        this.payload = payload;
        this.tenantId = tenantId;
    }

    /**
     * 创建新的事务上下文，自动生成事务 ID。
     *
     * @param stepName 步骤名称
     * @param payload 业务数据载荷
     * @param tenantId 租户 ID
     * @return 新的事务上下文
     */
    public static TransactionContext create(String stepName,
                                             Map<String, Object> payload,
                                             String tenantId) {
        String transactionId = "DT" + System.currentTimeMillis()
                + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new TransactionContext(transactionId, stepName, payload, tenantId);
    }

    /**
     * 基于当前上下文创建新的步骤上下文（复用 transactionId 和 tenantId）。
     *
     * @param newStepName 新步骤名称
     * @return 新步骤的事务上下文
     */
    public TransactionContext forStep(String newStepName) {
        return new TransactionContext(this.transactionId, newStepName, this.payload, this.tenantId);
    }

    public String getTransactionId() { return transactionId; }

    public String getStepName() { return stepName; }

    public Map<String, Object> getPayload() { return payload; }

    public String getTenantId() { return tenantId; }

    /**
     * 从 payload 中获取指定键的值。
     *
     * @param key 键名
     * @return 值；若不存在则返回 null
     */
    public Object get(String key) {
        return payload != null ? payload.get(key) : null;
    }

    /**
     * 从 payload 中获取指定键的值，并转换为指定类型。
     *
     * @param key 键名
     * @param type 目标类型
     * @return 转换后的值；若不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        return type.cast(value);
    }
}
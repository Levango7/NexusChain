package org.nexus.signing.approval;

import java.util.Map;
import java.util.Set;

/**
 * 审批记录存储抽象。
 *
 * <p>支持两种实现：
 * <ul>
 *   <li>{@link java.util.concurrent.ConcurrentHashMap} 内存存储（默认，单实例）</li>
 *   <li>{@link FileBasedApprovalStore} 文件持久化存储（重启不丢失）</li>
 * </ul>
 *
 * @since 2.15.0
 */
public interface ApprovalStore {

    /** 存储审批请求 */
    void put(String requestId, SigningApprovalRequest request);

    /** 获取审批请求 */
    SigningApprovalRequest get(String requestId);

    /** 移除审批请求并返回 */
    SigningApprovalRequest remove(String requestId);

    /** 更新（覆盖）审批请求 */
    void save(String requestId, SigningApprovalRequest request);

    /**
     * 原子状态迁移（CAS）：仅当当前状态等于 {@code expected} 时迁移到 {@code to}。
     *
     * <p>资金安全关键路径：同一审批请求不允许被并发重复执行（双重签名）。
     * 实现必须保证 check-and-set 的原子性：
     * <ul>
     *   <li>内存实现：基于 {@link ConcurrentHashMap#compute} 的 per-key 原子操作</li>
     *   <li>文件实现：内存缓存 compute 原子迁移后同步落盘</li>
     *   <li>JPA 实现：依赖实体 {@code @Version} 乐观锁，并发迁移时后提交者失败</li>
     * </ul>
     *
     * @param requestId 审批请求 ID
     * @param expected  期望的当前状态
     * @param to        目标状态
     * @return true 表示迁移成功；false 表示请求不存在、状态不匹配或并发冲突
     */
    boolean compareAndTransition(String requestId, SigningApprovalRequest.Status expected,
                                 SigningApprovalRequest.Status to);

    /** 遍历所有条目 */
    Set<Map.Entry<String, SigningApprovalRequest>> entrySet();

    /** 存储条目数 */
    int size();
}
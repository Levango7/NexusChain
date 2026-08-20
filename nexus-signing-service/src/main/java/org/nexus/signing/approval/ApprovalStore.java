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

    /** 遍历所有条目 */
    Set<Map.Entry<String, SigningApprovalRequest>> entrySet();

    /** 存储条目数 */
    int size();
}
package org.nexus.bridge.messaging;

import java.util.Optional;

/**
 * 消息存储接口。
 *
 * <p>提供已中继 / 已执行消息的持久化能力，用于：</p>
 * <ul>
 *   <li><b>消息去重</b> — 通过 messageId 防止同一消息被重复中继</li>
 *   <li><b>顺序保证</b> — 通过 (sourceChain, nonce) 单调递增检查防止乱序</li>
 *   <li><b>执行追溯</b> — 记入执行交易哈希，便于审计与争议仲裁</li>
 * </ul>
 *
 * <p>生产环境应使用 Redis / 数据库实现以支持多实例共享状态；
 * {@link InMemoryMessageStore} 仅供测试与单实例开发使用。</p>
 *
 * @since 1.9.2
 */
public interface MessageStore {

    /**
     * 保存一条消息。
     *
     * @param message 跨链消息
     * @return 若此前不存在相同 messageId 返回 true，否则返回 false（重复）
     */
    boolean save(CrossChainMessage message);

    /**
     * 按 messageId 查询消息。
     *
     * @param messageId 消息 ID
     * @return 消息 Optional
     */
    Optional<CrossChainMessage> findById(String messageId);

    /**
     * 判断 messageId 是否已存在（用于去重检查）。
     *
     * @param messageId 消息 ID
     * @return 存在返回 true
     */
    boolean existsById(String messageId);

    /**
     * 获取指定源链已中继的最大 nonce。
     *
     * <p>用于顺序保证：新消息的 nonce 必须严格大于该值。</p>
     *
     * @param sourceChain 源链 ID
     * @return 最大 nonce；若该链尚无消息返回 -1
     */
    long getMaxNonce(String sourceChain);

    /**
     * 记入目标链执行交易哈希。
     *
     * @param messageId 消息 ID
     * @param txHash    目标链交易哈希
     * @return 更新成功返回 true；消息不存在返回 false
     */
    boolean recordExecution(String messageId, String txHash);

    /**
     * 查询某消息的目标链执行交易哈希。
     *
     * @param messageId 消息 ID
     * @return 交易哈希 Optional
     */
    Optional<String> getExecutionTxHash(String messageId);

    /**
     * 清空存储（仅供测试使用）。
     */
    void clear();
}
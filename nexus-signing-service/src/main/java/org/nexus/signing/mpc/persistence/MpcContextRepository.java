package org.nexus.signing.mpc.persistence;

import java.util.List;
import java.util.Optional;

/**
 * MPC 协议上下文关系库持久化接口。
 *
 * <p>协议上下文（{@link MpcProtocolContext}）记录一个签名会话在每一轮的
 * 中间状态，用于崩溃恢复、审计与 WAL 协同。</p>
 *
 * <p>当前默认实现 {@link InMemoryMpcContextRepository} 用于 composite build
 * 占位与测试。切换 JPA 步骤见 {@link MpcWalletRepository}。</p>
 */
public interface MpcContextRepository {

    /**
     * 保存或更新上下文。
     *
     * @param context 协议上下文
     * @return 保存后的实体
     */
    MpcProtocolContext save(MpcProtocolContext context);

    /**
     * 按 (sessionId, round) 查找上下文。
     *
     * @param sessionId 会话 ID
     * @param round     轮次号
     * @return 上下文（可选）
     */
    Optional<MpcProtocolContext> findBySessionAndRound(String sessionId, int round);

    /**
     * 列出指定会话的所有上下文（按轮次升序）。
     *
     * @param sessionId 会话 ID
     * @return 上下文列表
     */
    List<MpcProtocolContext> findBySessionId(String sessionId);

    /**
     * 删除指定会话的所有上下文。
     *
     * @param sessionId 会话 ID
     */
    void deleteBySessionId(String sessionId);
}

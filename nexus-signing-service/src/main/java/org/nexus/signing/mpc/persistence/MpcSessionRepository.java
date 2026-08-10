package org.nexus.signing.mpc.persistence;

import org.nexus.signing.mpc.MpcSignSession;

import java.util.List;
import java.util.Optional;

/**
 * MPC 签名会话关系库持久化接口。
 *
 * <p>每个签名会话（{@link MpcSignSession}）的状态、已收集份额、最终签名
 * 持久化到关系库，用于审计与崩溃恢复。接口形状与 JPA Repository 一致。</p>
 *
 * <p>当前默认实现 {@link InMemoryMpcSessionRepository} 用于 composite build
 * 占位与测试。切换 JPA 步骤见 {@link MpcWalletRepository}。</p>
 */
public interface MpcSessionRepository {

    /**
     * 保存或更新会话。
     *
     * @param session 会话实体
     * @return 保存后的实体
     */
    MpcSignSession save(MpcSignSession session);

    /**
     * 按 ID 查找会话。
     *
     * @param sessionId 会话 ID
     * @return 会话实体（可选）
     */
    Optional<MpcSignSession> findById(String sessionId);

    /**
     * 列出指定钱包的所有会话（按创建时间倒序）。
     *
     * @param walletId 钱包 ID
     * @return 会话列表
     */
    List<MpcSignSession> findByWalletId(String walletId);

    /**
     * 列出指定状态的会话。
     *
     * @param status 会话状态
     * @return 会话列表
     */
    List<MpcSignSession> findByStatus(MpcSignSession.SessionStatus status);

    /**
     * 删除会话。
     *
     * @param sessionId 会话 ID
     */
    void deleteById(String sessionId);
}
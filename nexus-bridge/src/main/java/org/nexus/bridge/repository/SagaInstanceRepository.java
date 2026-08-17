package org.nexus.bridge.repository;

import org.nexus.bridge.saga.SagaInstance;
import org.nexus.bridge.saga.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Saga 实例 Repository（P2-F2）。
 *
 * <p>提供按状态查询、按关联交易查询等能力，用于
 * 崩溃恢复扫描与人工审计。</p>
 *
 * @since 2.2.0
 */
@Repository
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, String> {

    /**
     * 按状态查询 Saga 实例。
     *
     * @param state Saga 状态
     * @return 实例列表
     */
    List<SagaInstance> findByState(SagaState state);

    /**
     * 按关联桥交易 ID 查询。
     *
     * @param relatedTxId 桥交易 ID
     * @return 实例列表
     */
    List<SagaInstance> findByRelatedTxId(String relatedTxId);

    /**
     * 查询非终态 Saga（用于崩溃恢复扫描）。
     *
     * @param states 非终态状态集合
     * @return 实例列表
     */
    List<SagaInstance> findByStateIn(List<SagaState> states);
}
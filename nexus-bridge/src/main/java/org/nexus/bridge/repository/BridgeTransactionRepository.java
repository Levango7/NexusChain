package org.nexus.bridge.repository;

import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.model.BridgeTransaction.BridgeTxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BridgeTransactionRepository extends JpaRepository<BridgeTransaction, String> {

    Optional<BridgeTransaction> findBySourceTxHash(String sourceTxHash);

    List<BridgeTransaction> findByStatus(BridgeTxStatus status);

    List<BridgeTransaction> findBySourceChainIdAndTargetChainId(String sourceChainId, String targetChainId);

    long countByStatusIn(List<BridgeTxStatus> statuses);

    /**
     * P2-F2：按源链 ID + 状态集合聚合金额之和（资金守恒校验用）。
     *
     * @param sourceChainId 源链 ID
     * @param statuses      状态集合
     * @return 金额之和（无数据返回 0）
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM BridgeTransaction t "
            + "WHERE t.sourceChainId = :chainId AND t.status IN :statuses")
    long sumAmountBySourceChainIdAndStatusIn(@Param("chainId") String sourceChainId,
                                              @Param("statuses") List<BridgeTxStatus> statuses);

    /**
     * P2-F2：按目标链 ID + 状态集合聚合金额之和（资金守恒校验用）。
     *
     * @param targetChainId 目标链 ID
     * @param statuses      状态集合
     * @return 金额之和（无数据返回 0）
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM BridgeTransaction t "
            + "WHERE t.targetChainId = :chainId AND t.status IN :statuses")
    long sumAmountByTargetChainIdAndStatusIn(@Param("chainId") String targetChainId,
                                             @Param("statuses") List<BridgeTxStatus> statuses);

    /**
     * P2-F2：查询所有出现过的源链 ID（资金守恒校验遍历用）。
     *
     * @return 源链 ID 列表
     */
    @Query("SELECT DISTINCT t.sourceChainId FROM BridgeTransaction t WHERE t.sourceChainId IS NOT NULL")
    List<String> findDistinctSourceChainIds();

    /**
     * P2-F2：查询所有出现过的目标链 ID（资金守恒校验遍历用）。
     *
     * @return 目标链 ID 列表
     */
    @Query("SELECT DISTINCT t.targetChainId FROM BridgeTransaction t WHERE t.targetChainId IS NOT NULL")
    List<String> findDistinctTargetChainIds();
}
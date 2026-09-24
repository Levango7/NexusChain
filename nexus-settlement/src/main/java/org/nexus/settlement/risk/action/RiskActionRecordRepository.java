package org.nexus.settlement.risk.action;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 风控处置记录 Repository。
 */
@Repository
public interface RiskActionRecordRepository extends JpaRepository<RiskActionRecord, Long> {

    /**
     * 根据商户 ID 查找处置记录。
     *
     * @param merchantId 商户 ID
     * @return 处置记录列表
     */
    List<RiskActionRecord> findByMerchantId(Long merchantId);

    /**
     * 根据处置状态查找记录。
     *
     * @param actionStatus 处置状态
     * @return 处置记录列表
     */
    List<RiskActionRecord> findByActionStatus(RiskActionRecord.ActionStatus actionStatus);

    /**
     * 根据处置动作类型查找记录。
     *
     * @param actionType 处置动作类型
     * @return 处置记录列表
     */
    List<RiskActionRecord> findByActionType(RiskActionRecord.ActionType actionType);
}
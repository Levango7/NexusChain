package org.nexus.gateway.fundtransfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 资金调拨规则 Repository。
 *
 * <p>提供按规则编号、触发类型、启用状态等条件查询调拨规则的方法。</p>
 */
@Repository
public interface TransferRuleRepository extends JpaRepository<TransferRule, Long> {

    /**
     * 按规则编号查询。
     *
     * @param ruleCode 规则编号
     * @return 调拨规则（可能为空）
     */
    Optional<TransferRule> findByRuleCode(String ruleCode);

    /**
     * 按触发类型查询所有规则。
     *
     * @param triggerType 触发类型
     * @return 调拨规则列表
     */
    List<TransferRule> findByTriggerType(TriggerType triggerType);

    /**
     * 按启用状态查询所有规则。
     *
     * @param enabled 是否启用
     * @return 调拨规则列表
     */
    List<TransferRule> findByEnabled(Boolean enabled);

    /**
     * 按触发类型和启用状态查询规则。
     *
     * @param triggerType 触发类型
     * @param enabled 是否启用
     * @return 调拨规则列表
     */
    List<TransferRule> findByTriggerTypeAndEnabled(TriggerType triggerType, Boolean enabled);
}
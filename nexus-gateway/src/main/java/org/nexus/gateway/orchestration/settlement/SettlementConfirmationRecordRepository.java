package org.nexus.gateway.orchestration.settlement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 链上结算确认记录 Repository（Wave 9-C1-1）。
 *
 * <p>提供按支付 ID、状态、时间等维度查询确认记录的能力，
 * 供 {@link ChainSettlementConfirmationService} 和
 * {@link ChainSettlementConfirmationController} 使用。</p>
 *
 * @since Wave 9-C1-1 链上结算确认
 */
@Repository
public interface SettlementConfirmationRecordRepository extends JpaRepository<SettlementConfirmationRecord, Long> {

    /** 按支付 ID 查询确认记录（唯一）。 */
    Optional<SettlementConfirmationRecord> findByPaymentId(String paymentId);

    /** 按交易哈希查询确认记录。 */
    Optional<SettlementConfirmationRecord> findByTxHash(String txHash);

    /** 按状态查询所有确认记录（用于定时任务扫描 PENDING 记录）。 */
    List<SettlementConfirmationRecord> findByStatus(SettlementConfirmationStatus status);

    /** 按状态分页查询确认记录（用于 REST API 列表查询）。 */
    Page<SettlementConfirmationRecord> findByStatus(SettlementConfirmationStatus status, Pageable pageable);

    /** 按状态 + 支付 ID 查询（用于幂等校验）。 */
    Optional<SettlementConfirmationRecord> findByPaymentIdAndStatus(String paymentId, SettlementConfirmationStatus status);

    /** 检查支付 ID 是否已存在确认记录（幂等校验）。 */
    boolean existsByPaymentId(String paymentId);
}
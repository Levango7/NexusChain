package org.nexus.settlement.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对账记录仓储（JPA，账务核心持久化）。
 *
 * <p>消费方：InMemoryChainRecordSource / InMemoryBankRecordSource
 * （fetch/feed 落库委托），按 {@code source} 区分链上/银行记录。</p>
 */
@Repository
public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {

    /**
     * 按数据源查询对账记录（fetchChainRecords/fetchBankRecords）。
     *
     * @param source 数据源（CHAIN / BANK）
     * @return 该数据源的记录列表
     */
    List<SettlementRecord> findBySource(String source);

    /**
     * 按对账键 + 数据源查单条（幂等去重检查）。
     *
     * @param reference 对账键
     * @param source    数据源
     * @return 已存在的记录（幂等命中）
     */
    Optional<SettlementRecord> findByReferenceAndSource(String reference, String source);

    /**
     * 按数据源清空记录（clear 委托）。
     *
     * @param source 数据源
     */
    void deleteBySource(String source);
}
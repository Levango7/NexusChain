package org.nexus.gateway.clearing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 清算结算记录 Repository。
 *
 * <p>提供按批次编号、商户ID、入账状态等条件查询清算结算记录的方法。</p>
 */
@Repository
public interface ClearingSettlementRecordRepository extends JpaRepository<ClearingSettlementRecord, Long> {

    /**
     * 按记录编号查询。
     *
     * @param recordNo 记录编号
     * @return 清算结算记录（可能为空）
     */
    Optional<ClearingSettlementRecord> findByRecordNo(String recordNo);

    /**
     * 按清算批次编号查询所有记录。
     *
     * @param batchNo 清算批次编号
     * @return 清算结算记录列表
     */
    List<ClearingSettlementRecord> findByBatchNo(String batchNo);

    /**
     * 按商户ID查询所有记录。
     *
     * @param merchantId 商户ID
     * @return 清算结算记录列表
     */
    List<ClearingSettlementRecord> findByMerchantId(Long merchantId);

    /**
     * 按入账状态查询所有记录。
     *
     * @param bookingStatus 入账状态
     * @return 清算结算记录列表
     */
    List<ClearingSettlementRecord> findByBookingStatus(BookingStatus bookingStatus);

    /**
     * 按清算批次编号和入账状态查询记录。
     *
     * @param batchNo 清算批次编号
     * @param bookingStatus 入账状态
     * @return 清算结算记录列表
     */
    List<ClearingSettlementRecord> findByBatchNoAndBookingStatus(String batchNo, BookingStatus bookingStatus);

    /**
     * 按记录类型查询记录。
     *
     * @param recordType 记录类型
     * @return 清算结算记录列表
     */
    List<ClearingSettlementRecord> findByRecordType(String recordType);

    /**
     * 按记录类型和入账状态查询记录。
     *
     * @param recordType 记录类型
     * @param bookingStatus 入账状态
     * @return 清算结算记录列表
     */
    List<ClearingSettlementRecord> findByRecordTypeAndBookingStatus(String recordType, BookingStatus bookingStatus);
}
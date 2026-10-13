package org.nexus.gateway.clearing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    /**
     * CAS 认领一条待执行的划拨指令（PENDING → EXECUTING）。
     *
     * <p>只有把状态从 PENDING 原子改掉的那个执行者拿到认领权；返回 0 表示已被其他副本
     * （{@code values-prod.yaml} 的 {@code nexus-gateway.replicaCount: 3}）认领或状态已流转，
     * 调用方必须放弃执行，不能再触发动账。</p>
     *
     * <p>口径与同仓既有先例一致：{@code RefundRequestRepository#claimForExecution}
     * （防并发重复执行导致多次不可逆打款）。</p>
     *
     * @param id  指令记录 ID
     * @param now 认领时间
     * @return 受影响行数：1=认领成功，0=认领失败
     */
    @Modifying
    @Query("UPDATE ClearingSettlementRecord r "
            + "SET r.bookingStatus = org.nexus.gateway.clearing.BookingStatus.EXECUTING, "
            + "r.updatedAt = :now "
            + "WHERE r.id = :id "
            + "AND r.bookingStatus = org.nexus.gateway.clearing.BookingStatus.PENDING")
    int claimForExecution(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 收尾一条已认领的指令（EXECUTING → BOOKED / FAILED）。
     *
     * <p>刻意不改 {@code settledAt}：那是"应结算时间"，属业务字段，不能拿完成时间覆盖。</p>
     *
     * @param id           指令记录 ID
     * @param to           终态
     * @param errorMessage 失败原因（成功时传 null）
     * @param now          收尾时间
     * @return 受影响行数：1=收尾成功，0=该记录不在 EXECUTING（异常并发场景，交由对账）
     */
    @Modifying
    @Query("UPDATE ClearingSettlementRecord r "
            + "SET r.bookingStatus = :to, r.errorMessage = :errorMessage, r.updatedAt = :now "
            + "WHERE r.id = :id "
            + "AND r.bookingStatus = org.nexus.gateway.clearing.BookingStatus.EXECUTING")
    int finishExecution(@Param("id") Long id, @Param("to") BookingStatus to,
                        @Param("errorMessage") String errorMessage,
                        @Param("now") LocalDateTime now);
}
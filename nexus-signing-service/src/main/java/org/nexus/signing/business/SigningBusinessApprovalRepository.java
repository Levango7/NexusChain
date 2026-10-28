package org.nexus.signing.business;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * A2 业务批准凭据仓储（{@code signing_business_approval} 表，V5）。
 *
 * <p>核心是 {@link #consumeIfRegistered} 的<b>条件更新 CAS</b>：
 * 并发两个 signTransfer 携同一凭据时，只有一个能把 REGISTERED → CONSUMED
 * （返回 1），另一个返回 0 并被调用方判为"已消费"。这是本表唯一的并发路径，
 * 因此不用 JPA 乐观锁（见实体 javadoc）。</p>
 */
@Repository
public interface SigningBusinessApprovalRepository
        extends JpaRepository<SigningBusinessApprovalEntity, Long> {

    /** 按业务单号取登记行。 */
    java.util.Optional<SigningBusinessApprovalEntity> findByBusinessApprovalId(String businessApprovalId);

    /** 业务单号是否已登记。 */
    boolean existsByBusinessApprovalId(String businessApprovalId);

    /**
     * CAS 消费：REGISTERED → CONSUMED。
     *
     * @return 1 = 本调用赢得消费权；0 = 已被消费/不存在
     */
    @Modifying
    @Query("UPDATE SigningBusinessApprovalEntity e "
            + "SET e.state = 'CONSUMED', e.consumedAt = :now, e.version = e.version + 1 "
            + "WHERE e.businessApprovalId = :businessApprovalId AND e.state = 'REGISTERED'")
    int consumeIfRegistered(@Param("businessApprovalId") String businessApprovalId,
                            @Param("now") java.time.LocalDateTime now);

    /**
     * 广播失败后的回退：CONSUMED → REGISTERED（仅对本次刚消费、尚未被他人接手的行）。
     *
     * <p>与审批腿的 {@code revertExecuting} 同型：doSignAndBroadcast 的失败路径
     * （无签名键/地址非法/nonce 失败/交易构造失败）资金未转出，凭据必须可重试，
     * 否则一次渠道故障就烧掉一张已批准的凭据。</p>
     *
     * @return 1 = 回退成功；0 = 状态已变（被并发处置，交人工）
     */
    @Modifying
    @Query("UPDATE SigningBusinessApprovalEntity e "
            + "SET e.state = 'REGISTERED', e.consumedAt = NULL, e.version = e.version + 1 "
            + "WHERE e.businessApprovalId = :businessApprovalId AND e.state = 'CONSUMED'")
    int revertConsumed(@Param("businessApprovalId") String businessApprovalId);
}

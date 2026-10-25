package org.nexus.gateway.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** {@link ApprovalExecutionLink} 仓储。 */
@Repository
public interface ApprovalExecutionLinkRepository extends JpaRepository<ApprovalExecutionLink, Long> {

    Optional<ApprovalExecutionLink> findByRequestKey(String requestKey);

    /**
     * 取一批"没有轮询调用方"的非终态链接（按最久未更新优先），供定时推进任务使用。
     *
     * <p>必须按 {@code requestTypes} 白名单取：只有调用方自己会推进执行的类型
     * （如 CHAIN/CONSORTIUM 支付）不能出现在白名单里，否则会出现
     * "业务已判失败、后台又把钱转走"。见 {@link ApprovalExecutionReconciler}。</p>
     *
     * @param requestTypes 业务类型白名单
     * @param statuses     目标状态集合（非终态）
     * @param cutoff       只取早于此时刻更新的链接（让出刚写入的一轮，避免与请求线程竞争）
     * @return 最多 100 条
     */
    List<ApprovalExecutionLink> findTop100ByRequestTypeInAndStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<String> requestTypes,
            Collection<ApprovalExecutionLink.Status> statuses,
            LocalDateTime cutoff);

    /**
     * 只读存量盘点：按业务类型统计处于非终态的链接数量与"最久一条"的更新时间。
     *
     * <p>与推进白名单<b>无关</b>——覆盖全部 {@code requestType}，包括被有意排除的
     * {@code CHAIN} / {@code CONSORTIUM} 支付（Board #234 §二：读与写分离）。
     * 没有这个入口时，"审批通过但无人推进"是完全不可见的，只能等商户投诉。</p>
     *
     * @param statuses 目标状态集合（通常为非终态）
     * @return 每个业务类型一行：类型、数量、最久更新时间
     */
    @Query("SELECT l.requestType AS requestType, COUNT(l.id) AS linkCount, "
            + "MIN(l.updatedAt) AS oldestUpdatedAt "
            + "FROM ApprovalExecutionLink l WHERE l.status IN :statuses GROUP BY l.requestType")
    List<StockRow> stockByStatus(@Param("statuses") Collection<ApprovalExecutionLink.Status> statuses);

    /** {@link #stockByStatus} 的投影行。 */
    interface StockRow {
        String getRequestType();

        long getLinkCount();

        LocalDateTime getOldestUpdatedAt();
    }
}

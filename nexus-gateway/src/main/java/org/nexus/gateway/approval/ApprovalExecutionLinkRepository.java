package org.nexus.gateway.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** {@link ApprovalExecutionLink} 仓储。 */
@Repository
public interface ApprovalExecutionLinkRepository extends JpaRepository<ApprovalExecutionLink, Long> {

    Optional<ApprovalExecutionLink> findByRequestKey(String requestKey);

    /**
     * 取一批非终态链接（按最久未更新优先），供定时推进任务使用。
     *
     * @param statuses 目标状态集合（非终态）
     * @return 最多 100 条
     */
    List<ApprovalExecutionLink> findTop100ByStatusInOrderByUpdatedAtAsc(
            Collection<ApprovalExecutionLink.Status> statuses);
}

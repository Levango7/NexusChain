package org.nexus.gateway.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 商户入驻申请 JPA Repository。
 *
 * <p>提供按状态查询申请列表的能力，支持审核人员筛选待审核申请。</p>
 */
@Repository
public interface MerchantApplicationRepository extends JpaRepository<MerchantApplication, Long> {

    /** 按状态查询申请列表。 */
    List<MerchantApplication> findByStatus(ApplicationStatus status);

    /** 按状态查询，按提交时间升序排列（先提交先审核）。 */
    List<MerchantApplication> findByStatusOrderBySubmittedAtAsc(ApplicationStatus status);

    /** 按审核人员 ID 查询其正在审核的申请。 */
    List<MerchantApplication> findByReviewerIdAndStatus(String reviewerId, ApplicationStatus status);
}
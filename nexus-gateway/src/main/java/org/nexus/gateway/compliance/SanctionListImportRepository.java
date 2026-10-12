package org.nexus.gateway.compliance;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 制裁名单导入台账（append-only；只提供查询与插入）。 */
@Repository
public interface SanctionListImportRepository extends JpaRepository<SanctionListImportEntity, Long> {

    /** 最近的导入记录（按时间倒序，用于"当前名单版本"一眼可见）。 */
    List<SanctionListImportEntity> findAllByOrderByImportedAtDesc(Pageable pageable);
}

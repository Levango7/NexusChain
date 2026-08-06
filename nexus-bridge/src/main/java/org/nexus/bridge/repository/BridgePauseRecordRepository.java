package org.nexus.bridge.repository;

import org.nexus.bridge.model.BridgePauseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 桥暂停状态记录 Repository。
 *
 * @since 1.2
 */
@Repository
public interface BridgePauseRecordRepository extends JpaRepository<BridgePauseRecord, String> {
}
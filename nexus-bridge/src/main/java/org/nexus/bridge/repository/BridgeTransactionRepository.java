package org.nexus.bridge.repository;

import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.model.BridgeTransaction.BridgeTxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BridgeTransactionRepository extends JpaRepository<BridgeTransaction, String> {

    Optional<BridgeTransaction> findBySourceTxHash(String sourceTxHash);

    List<BridgeTransaction> findByStatus(BridgeTxStatus status);

    List<BridgeTransaction> findBySourceChainIdAndTargetChainId(String sourceChainId, String targetChainId);

    long countByStatusIn(List<BridgeTxStatus> statuses);
}
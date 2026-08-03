package org.nexus.consortium.dao;

import org.springframework.data.domain.Pageable;
import org.nexus.consortium.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TransactionDao extends JpaRepository<Transaction, byte[]> {
    List<Transaction> findByBlockHashIn(Collection<byte[]> blockHashes);

    List<Transaction> findByBlockHashOrderByPosition(byte[] blockHash, Pageable pageable);

    List<Transaction> findByFromOrderByHeightAscPositionAsc(byte[] from, Pageable pageable);

    List<Transaction> findByFromAndTypeOrderByHeightAscPositionAsc(byte[] from, int type, Pageable pageable);

    List<Transaction> findByToOrderByHeightAscPositionAsc(byte[] to, Pageable pageable);

    List<Transaction> findByToAndTypeOrderByHeightAscPositionAsc(byte[] to, int type, Pageable pageable);

    List<Transaction> findByFromAndToOrderByHeightAscPositionAsc(byte[] from, byte[] to, Pageable pageable);

    List<Transaction> findByFromAndToAndTypeOrderByHeightAscPositionAsc(byte[] from, byte[] to, int type, Pageable pageable);

    List<Transaction> findByPayloadOrderByHeightAscPositionAsc(byte[] payload, Pageable pageable);

    List<Transaction> findByPayloadAndTypeOrderByHeightAscPositionAsc(byte[] payload, int type, Pageable pageable);

    List<Transaction> findByHeightOrderByPosition(long height, Pageable pageable);

    boolean existsByPayload(byte[] payload);
}

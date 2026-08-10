package org.nexus.consortium.dao;

import org.nexus.consortium.entity.Block;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BlockDao extends JpaRepository<Block, byte[]> {
    Optional<Block> findTopByOrderByHeightDesc();
    Optional<Block> findByHeight(long height);
}

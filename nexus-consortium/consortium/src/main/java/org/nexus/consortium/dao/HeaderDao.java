package org.nexus.consortium.dao;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.nexus.consortium.entity.Header;

import java.util.List;
import java.util.Optional;

public interface HeaderDao extends JpaRepository<Header, byte[]> {
    List<Header> findByHeightBetweenOrderByHeight(long start, long end);

    List<Header> findByHeightBetweenOrderByHeightAsc(long start, long end, Pageable pageable);

    List<Header> findByHeightBetweenOrderByHeightDesc(long start, long end, Pageable pageable);

    Optional<Header> findTopByOrderByHeightDesc();

    List<Header> findByHeightGreaterThanEqual(long height, Pageable pageable);

    Optional<Header> findByHeight(long height);
}

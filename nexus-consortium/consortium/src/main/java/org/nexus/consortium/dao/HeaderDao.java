package org.nexus.consortium.dao;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.nexus.consortium.entity.Header;

import java.util.List;
import java.util.Optional;

public interface HeaderDao extends JpaRepository<Header, byte[]> {
    List<Header> findByHeightBetweenOrderByHeight(long start, long end);

    List<Header> findByHeightBetweenOrderByHeightAsc(long start, long end, Pageable pageable);

    List<Header> findByHeightBetweenOrderByHeightDesc(long start, long end, Pageable pageable);

    /**
     * 显式 JPQL（修复：派生查询 findTopByOrderByHeightDesc 在 H2 下排序异常，
     * 返回非最大高度——改为 ORDER BY 后取 Pageable 首条，跨方言一致）。
     */
    @Query("SELECT h FROM Header h ORDER BY h.height DESC")
    List<Header> findBestByExplicitOrder(Pageable pageable);

    List<Header> findByHeightGreaterThanEqual(long height, Pageable pageable);

    Optional<Header> findByHeight(long height);
}

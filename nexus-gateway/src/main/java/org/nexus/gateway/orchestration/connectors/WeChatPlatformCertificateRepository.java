package org.nexus.gateway.orchestration.connectors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 微信平台证书 JPA 仓储。
 */
@Repository
public interface WeChatPlatformCertificateRepository extends JpaRepository<WeChatPlatformCertificate, Long> {

    /**
     * 按证书序列号查找。
     *
     * @param serialNo 证书序列号
     * @return 证书 Optional
     */
    Optional<WeChatPlatformCertificate> findBySerialNo(String serialNo);

    /**
     * 查找指定状态且未过期的证书。
     *
     * @param status     证书状态
     * @param expireTime 过期时间阈值
     * @return 证书列表
     */
    List<WeChatPlatformCertificate> findByStatusAndExpireTimeAfter(
            CertificateStatus status, Instant expireTime);

    /**
     * 按状态查找并按过期时间降序排列。
     *
     * @param status 证书状态
     * @return 证书列表（过期时间降序）
     */
    List<WeChatPlatformCertificate> findByStatusOrderByExpireTimeDesc(
            CertificateStatus status);
}
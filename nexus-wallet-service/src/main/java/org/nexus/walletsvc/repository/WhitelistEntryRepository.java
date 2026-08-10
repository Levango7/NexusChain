package org.nexus.walletsvc.repository;

import org.nexus.walletsvc.entity.WhitelistEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 地址白名单 Repository，对应 {@code address_whitelist} 表。
 *
 * <p>设计文档 §4.2.2 / §4.4.2：统一替代 {@code DefaultAddressWhitelistService.entries}
 * 与 {@code DefaultApprovalPolicy.whitelist} 两套内存存储。</p>
 */
@Repository
public interface WhitelistEntryRepository extends JpaRepository<WhitelistEntryEntity, Long> {

    /**
     * 按地址查询白名单记录（含非活跃记录，用于软删除后重新激活等场景）。
     */
    Optional<WhitelistEntryEntity> findByAddress(String address);

    /**
     * 按商户 ID 查询全部白名单记录（含非活跃）。
     */
    List<WhitelistEntryEntity> findByMerchantId(String merchantId);

    /**
     * 按商户 ID 查询活跃白名单记录（{@code active=true}）。
     *
     * <p>对应 {@code DefaultAddressWhitelistService.listByMerchant()}，
     * 命中索引 {@code idx_merchant_active (merchant_id, active)}。</p>
     */
    List<WhitelistEntryEntity> findByMerchantIdAndActiveTrue(String merchantId);

    /**
     * 判断地址是否已存在（不论 active 状态）。
     *
     * <p>用于 {@code addWhitelist()} 时校验地址唯一性，避免触发
     * {@code DataIntegrityViolationException}。</p>
     */
    boolean existsByAddress(String address);

    /**
     * 判断地址是否在活跃白名单中（{@code active=true}）。
     *
     * <p>对应 {@code DefaultAddressWhitelistService.isWhitelisted()} 与
     * {@code DefaultApprovalPolicy.isAddressWhitelisted()}，消除双重存储后
     * 两者查询同一物理表（设计文档 §2.2 / §4.4.2 / §4.4.4）。</p>
     */
    boolean existsByAddressAndActiveTrue(String address);
}
package org.nexus.walletsvc.whitelist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.nexus.walletsvc.entity.WhitelistEntryEntity;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Default address whitelist service implementation.
 *
 * <p>Manages approved withdrawal addresses per merchant with a persistent
 * store backed by the {@code address_whitelist} table (via
 * {@link WhitelistEntryRepository}), enforcing a configurable first-time
 * withdrawal delay:</p>
 * <ul>
 *   <li>{@link #addWhitelist}：校验地址 → 持久化条目（addedAt=now，
 *       firstWithdrawalAvailableAt = now + 延迟）→ 审计日志</li>
 *   <li>{@link #removeWhitelist}：标记条目为非活跃（软删除，active=false）→ 审计日志</li>
 *   <li>{@link #isWhitelisted}：仅当条目存在且 active 时返回 true</li>
 *   <li>{@link #checkFirstTimeWithdrawal}：白名单内但延迟未到期返回 true
 *       （应阻断或走增强审批）</li>
 * </ul>
 *
 * <p>Phase 4 改造（设计文档 §4.4.2）：原进程内并发 Map 内存存储
 * 替换为 {@link WhitelistEntryRepository} 持久化，写操作标注 {@code @Transactional}
 * 以纳入 Seata AT 分支事务。返回类型保持 {@link WhitelistEntry} DTO 以维持
 * {@code AddressWhitelistService} 接口契约与 {@code WalletController} 序列化兼容性，
 * 内部做 Entity ↔ DTO 转换。</p>
 *
 * <p>首次提币延迟时长由 {@code nexus.wallet.whitelist.first-withdrawal-delay-hours}
 * 配置（默认 24 小时）。</p>
 *
 * <p>迁移历史：原位于 {@code org.nexus.wallet.wallet.whitelist.DefaultAddressWhitelistService}
 * （nexus-exchange-wallet），在 Phase 2 微服务化中迁移至 nexus-wallet-service
 * （新包 {@code org.nexus.walletsvc.whitelist}）。</p>
 */
@Service
public class DefaultAddressWhitelistService implements AddressWhitelistService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAddressWhitelistService.class);

    /** First-time withdrawal delay in hours. */
    @Value("${nexus.wallet.whitelist.first-withdrawal-delay-hours:24}")
    private long firstWithdrawalDelayHours;

    /** Persistent whitelist store backed by the {@code address_whitelist} table. */
    private final WhitelistEntryRepository whitelistEntryRepository;

    public DefaultAddressWhitelistService(WhitelistEntryRepository whitelistEntryRepository) {
        this.whitelistEntryRepository = whitelistEntryRepository;
    }

    @Override
    @Transactional
    public WhitelistEntry addWhitelist(String address, String label, String merchantId) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("address is required");
        }
        if (!isValidAddress(address)) {
            throw new IllegalArgumentException("invalid chain address: " + address);
        }
        if (merchantId == null || merchantId.trim().isEmpty()) {
            throw new IllegalArgumentException("merchantId is required");
        }

        String normalizedAddress = address.trim();
        if (whitelistEntryRepository.existsByAddress(normalizedAddress)) {
            throw new IllegalStateException("address already whitelisted: " + normalizedAddress);
        }

        LocalDateTime now = LocalDateTime.now();
        WhitelistEntryEntity entity = new WhitelistEntryEntity();
        entity.setAddress(normalizedAddress);
        entity.setLabel(label);
        entity.setMerchantId(merchantId.trim());
        entity.setAddedAt(now);
        entity.setFirstWithdrawalAvailableAt(now.plusHours(firstWithdrawalDelayHours));
        entity.setActive(true);

        WhitelistEntryEntity saved = whitelistEntryRepository.save(entity);
        log.info("Whitelist added: address={}, label={}, merchantId={}, firstWithdrawalAvailableAt={}",
                address, label, merchantId, saved.getFirstWithdrawalAvailableAt());
        return toDto(saved);
    }

    @Override
    @Transactional
    public void removeWhitelist(String address) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("address is required");
        }
        String normalizedAddress = address.trim();
        WhitelistEntryEntity entity = whitelistEntryRepository.findByAddress(normalizedAddress).orElse(null);
        if (entity == null) {
            log.warn("removeWhitelist: address not found: {}", address);
            return;
        }
        entity.setActive(false);
        whitelistEntryRepository.save(entity);
        log.info("Whitelist removed (soft delete): address={}", address);
    }

    @Override
    public boolean isWhitelisted(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }
        return whitelistEntryRepository.existsByAddressAndActiveTrue(address.trim());
    }

    @Override
    public boolean checkFirstTimeWithdrawal(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }
        WhitelistEntryEntity entity = whitelistEntryRepository.findByAddress(address.trim()).orElse(null);
        if (entity == null || !Boolean.TRUE.equals(entity.getActive())) {
            return false;
        }
        LocalDateTime availableAt = entity.getFirstWithdrawalAvailableAt();
        if (availableAt == null) {
            return false;
        }
        boolean delayInEffect = LocalDateTime.now().isBefore(availableAt);
        log.debug("checkFirstTimeWithdrawal: address={}, delayInEffect={}", address, delayInEffect);
        return delayInEffect;
    }

    /**
     * List all active whitelist entries for a merchant.
     *
     * @param merchantId merchant ID
     * @return active entries owned by the merchant
     */
    public List<WhitelistEntry> listByMerchant(String merchantId) {
        List<WhitelistEntry> result = new ArrayList<>();
        if (merchantId == null || merchantId.trim().isEmpty()) {
            return result;
        }
        List<WhitelistEntryEntity> entities = whitelistEntryRepository.findByMerchantIdAndActiveTrue(merchantId.trim());
        for (WhitelistEntryEntity entity : entities) {
            result.add(toDto(entity));
        }
        return result;
    }

    /**
     * Validate the address format (non-empty, reasonable length, no whitespace).
     * NexusChain addresses are typically base58 / hex strings of fixed length.
     */
    private boolean isValidAddress(String address) {
        String trimmed = address.trim();
        if (trimmed.length() < 20 || trimmed.length() > 128) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convert a {@link WhitelistEntryEntity} (JPA Entity) to a {@link WhitelistEntry} DTO.
     *
     * <p>Keeps the {@link AddressWhitelistService} interface contract returning the DTO
     * to preserve {@code WalletController} serialization compatibility.</p>
     */
    private WhitelistEntry toDto(WhitelistEntryEntity entity) {
        WhitelistEntry dto = new WhitelistEntry();
        dto.setAddress(entity.getAddress());
        dto.setLabel(entity.getLabel());
        dto.setMerchantId(entity.getMerchantId());
        dto.setAddedAt(entity.getAddedAt());
        dto.setFirstWithdrawalAvailableAt(entity.getFirstWithdrawalAvailableAt());
        dto.setActive(entity.getActive());
        return dto;
    }
}

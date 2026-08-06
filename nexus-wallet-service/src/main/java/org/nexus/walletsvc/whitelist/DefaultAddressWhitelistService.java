package org.nexus.walletsvc.whitelist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default address whitelist service implementation.
 *
 * <p>Manages approved withdrawal addresses per merchant with an in-memory
 * store, enforcing a configurable first-time withdrawal delay:</p>
 * <ul>
 *   <li>{@link #addWhitelist}：校验地址 → 写入条目（addedAt=now，
 *       firstWithdrawalAvailableAt = now + 延迟）→ 审计日志</li>
 *   <li>{@link #removeWhitelist}：标记条目为非活跃（软删除）→ 审计日志</li>
 *   <li>{@link #isWhitelisted}：仅当条目存在且 active 时返回 true</li>
 *   <li>{@link #checkFirstTimeWithdrawal}：白名单内但延迟未到期返回 true
 *       （应阻断或走增强审批）</li>
 * </ul>
 *
 * <p>存储为进程内内存表；生产环境需替换为持久化存储（保留接口契约即可）。
 * 首次提币延迟时长由 {@code nexus.wallet.whitelist.first-withdrawal-delay-hours}
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

    /** Whitelist store: address → entry. */
    private final Map<String, WhitelistEntry> entries = new ConcurrentHashMap<>();

    @Override
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

        WhitelistEntry entry = new WhitelistEntry(address.trim(), label, merchantId.trim());
        entry.setAddedAt(LocalDateTime.now());
        entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(firstWithdrawalDelayHours));
        entry.setActive(true);

        entries.put(entry.getAddress(), entry);
        log.info("Whitelist added: address={}, label={}, merchantId={}, firstWithdrawalAvailableAt={}",
                address, label, merchantId, entry.getFirstWithdrawalAvailableAt());
        return entry;
    }

    @Override
    public void removeWhitelist(String address) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("address is required");
        }
        WhitelistEntry entry = entries.get(address.trim());
        if (entry == null) {
            log.warn("removeWhitelist: address not found: {}", address);
            return;
        }
        entry.setActive(false);
        log.info("Whitelist removed (soft delete): address={}", address);
    }

    @Override
    public boolean isWhitelisted(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }
        WhitelistEntry entry = entries.get(address.trim());
        return entry != null && Boolean.TRUE.equals(entry.getActive());
    }

    @Override
    public boolean checkFirstTimeWithdrawal(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }
        WhitelistEntry entry = entries.get(address.trim());
        if (entry == null || !Boolean.TRUE.equals(entry.getActive())) {
            return false;
        }
        LocalDateTime availableAt = entry.getFirstWithdrawalAvailableAt();
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
        for (WhitelistEntry entry : entries.values()) {
            if (Boolean.TRUE.equals(entry.getActive())
                    && merchantId.trim().equals(entry.getMerchantId())) {
                result.add(entry);
            }
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
}
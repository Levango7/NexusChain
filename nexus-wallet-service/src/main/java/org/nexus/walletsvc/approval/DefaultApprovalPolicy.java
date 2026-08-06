package org.nexus.walletsvc.approval;

import org.nexus.sdk.signing.ApprovalPolicy;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Default tiered approval policy.
 *
 * <p>Required approvers scale with withdrawal amount:</p>
 * <ul>
 *   <li>amount &le; small threshold (&lt; 10,000): 1 approver</li>
 *   <li>amount &le; large threshold (&lt; 100,000): 2 approvers</li>
 *   <li>amount &gt; large threshold: 3 approvers</li>
 * </ul>
 *
 * <p>Phase 4 改造（设计文档 §4.4.4）：原进程内并发 Set 白名单存储
 * 替换为 {@link WhitelistEntryRepository} 查询，与
 * {@code DefaultAddressWhitelistService.isWhitelisted()} 查询同一物理表
 * （{@code address_whitelist}），消除 Phase 3 遗留的双重白名单存储问题（§2.2 / FR-P5）。
 * {@code addToWhitelist()} / {@code removeFromWhitelist()} 标记 {@code @Deprecated}，
 * 白名单写入统一通过 {@code DefaultAddressWhitelistService}（管理端点）进行；
 * 本类仅保留查询职责。</p>
 *
 * <p>迁移历史：原位于 {@code org.nexus.wallet.wallet.approval.DefaultApprovalPolicy}
 * （nexus-exchange-wallet），在 Phase 2 微服务化中迁移至 nexus-wallet-service
 * （新包 {@code org.nexus.walletsvc.approval}）。实现 {@code org.nexus.sdk.signing.ApprovalPolicy}
 * 共享接口（nexus-sdk）。</p>
 */
@Primary
@Component
public class DefaultApprovalPolicy implements ApprovalPolicy {

    /** Amount at or below this requires a single approver. */
    private static final BigDecimal SMALL_THRESHOLD = new BigDecimal("10000");

    /** Amount at or below this requires two approvers. */
    private static final BigDecimal LARGE_THRESHOLD = new BigDecimal("100000");

    /** Persistent whitelist store, shared with {@code DefaultAddressWhitelistService}. */
    private final WhitelistEntryRepository whitelistEntryRepository;

    public DefaultApprovalPolicy(WhitelistEntryRepository whitelistEntryRepository) {
        this.whitelistEntryRepository = whitelistEntryRepository;
    }

    @Override
    public int getRequiredApprovers(BigDecimal amount, String currency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount.compareTo(SMALL_THRESHOLD) <= 0) {
            return 1;
        }
        if (amount.compareTo(LARGE_THRESHOLD) <= 0) {
            return 2;
        }
        return 3;
    }

    @Override
    public boolean isAddressWhitelisted(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        return whitelistEntryRepository.existsByAddressAndActiveTrue(address);
    }

    /**
     * Add an address to the whitelist.
     *
     * @param address wallet address
     * @deprecated Phase 4 起白名单写入统一通过
     *             {@code DefaultAddressWhitelistService.addWhitelist()}
     *             （管理端点）进行；本类仅保留查询职责，此方法为 no-op。
     */
    @Deprecated
    public void addToWhitelist(String address) {
        // no-op: 白名单写入统一通过 DefaultAddressWhitelistService 管理（设计文档 §4.4.4）
    }

    /**
     * Remove an address from the whitelist.
     *
     * @param address wallet address
     * @deprecated Phase 4 起白名单移除统一通过
     *             {@code DefaultAddressWhitelistService.removeWhitelist()}
     *             （管理端点，软删除）进行；本类仅保留查询职责，此方法为 no-op。
     */
    @Deprecated
    public void removeFromWhitelist(String address) {
        // no-op: 白名单移除统一通过 DefaultAddressWhitelistService 管理（设计文档 §4.4.4）
    }
}

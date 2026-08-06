package org.nexus.wallet.wallet.approval;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

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
 * <p>Maintains an in-memory address whitelist; addresses not on the whitelist
 * are rejected at request time. Production wiring should source the whitelist
 * from a persistent store and load tier thresholds from configuration.</p>
 */
@Component
public class DefaultApprovalPolicy implements ApprovalPolicy {

    /** Amount at or below this requires a single approver. */
    private static final BigDecimal SMALL_THRESHOLD = new BigDecimal("10000");

    /** Amount at or below this requires two approvers. */
    private static final BigDecimal LARGE_THRESHOLD = new BigDecimal("100000");

    /** In-memory whitelist of permitted withdrawal addresses. */
    private final Set<String> whitelist = new CopyOnWriteArraySet<String>();

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
        return address != null && whitelist.contains(address);
    }

    /**
     * Add an address to the whitelist.
     *
     * @param address wallet address
     */
    public void addToWhitelist(String address) {
        if (address != null && !address.isEmpty()) {
            whitelist.add(address);
        }
    }

    /**
     * Remove an address from the whitelist.
     *
     * @param address wallet address
     */
    public void removeFromWhitelist(String address) {
        if (address != null) {
            whitelist.remove(address);
        }
    }
}

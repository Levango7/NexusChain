package org.nexus.gateway.compliance;

/**
 * Compliance service interface covering KYC verification, AML screening, and
 * suspicious activity reporting.
 *
 * <p>Implementations typically delegate to the {@code nexus-compliance} module
 * for sanctions list matching, PEP screening, and SAR (Suspicious Activity
 * Report) filing.</p>
 */
public interface ComplianceService {

    /**
     * Check the KYC status for a user.
     *
     * @param userId user ID
     * @return current KYC status
     */
    KycStatus checkKyc(String userId);

    /**
     * Screen a transaction against AML watchlists (sanctions, PEP, internal).
     *
     * @param transaction transaction to screen
     * @return AML screening result
     */
    AmlResult screenAml(Transaction transaction);

    /**
     * Report a transaction as suspicious and file a SAR (Suspicious Activity Report).
     *
     * @param transaction suspicious transaction
     * @param reason      free-text reason for the report
     */
    void reportSuspicious(Transaction transaction, String reason);
}
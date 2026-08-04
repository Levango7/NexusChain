package org.nexus.gateway.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default skeleton implementation of {@link ComplianceService}.
 *
 * <p>All methods are stubbed and log a TODO marker. Production wiring should
 * delegate to the {@code nexus-compliance} module for KYC verification,
 * sanctions list matching, PEP screening, and SAR filing.</p>
 */
@Service
public class DefaultComplianceService implements ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(DefaultComplianceService.class);

    @Override
    public KycStatus checkKyc(String userId) {
        // TODO: query nexus-compliance KYC store for the user's current status
        // TODO: return NONE if user is unknown, VERIFIED/ENHANCED/REJECTED per stored state
        log.warn("checkKyc not implemented: userId={}", userId);
        return KycStatus.NONE;
    }

    @Override
    public AmlResult screenAml(Transaction transaction) {
        // TODO: delegate to nexus-compliance AML engine for sanctions/PEP screening
        // TODO: populate hitLists, riskScore, needsManualReview
        log.warn("screenAml not implemented: transactionId={}", transaction.getTransactionId());
        return new AmlResult();
    }

    @Override
    public void reportSuspicious(Transaction transaction, String reason) {
        // TODO: file a SAR with nexus-compliance, persist audit trail, notify compliance officer
        log.warn("reportSuspicious not implemented: transactionId={}, reason={}",
                transaction.getTransactionId(), reason);
    }
}
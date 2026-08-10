package org.nexus.gateway.compliance;

import org.nexus.compliance.aml.AmlScreeningService;
import org.nexus.compliance.aml.ScreeningResult;
import org.nexus.compliance.aml.SuspiciousTransactionReport;
import org.nexus.compliance.kyc.KycLevel;
import org.nexus.compliance.kyc.KycService;
import org.nexus.gateway.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Gateway compliance facade that delegates to the {@code nexus-compliance} module.
 *
 * <p>KYC status queries go to {@link KycService}, transaction screening to
 * {@link AmlScreeningService#screen(Object)}, and suspicious activity reports are
 * filed via {@link AmlScreeningService#fileSuspiciousReport(SuspiciousTransactionReport)}
 * with an audit trail entry written by the gateway's {@link AuditLogService}.</p>
 */
@Service
public class DefaultComplianceService implements ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(DefaultComplianceService.class);

    private final KycService kycService;
    private final AmlScreeningService amlScreeningService;
    private final AuditLogService auditLog;

    public DefaultComplianceService(KycService kycService,
                                    AmlScreeningService amlScreeningService,
                                    AuditLogService auditLog) {
        this.kycService = kycService;
        this.amlScreeningService = amlScreeningService;
        this.auditLog = auditLog;
    }

    @Override
    public KycStatus checkKyc(String userId) {
        if (userId == null || userId.isBlank()) {
            return KycStatus.NONE;
        }
        KycLevel level = kycService.getKycStatus(userId);
        if (level == null) {
            return KycStatus.NONE;
        }
        // INSTITUTIONAL（机构认证）在网关侧视为完全认证
        return switch (level) {
            case NONE -> KycStatus.NONE;
            case BASIC -> KycStatus.BASIC;
            case ENHANCED -> KycStatus.ENHANCED;
            case INSTITUTIONAL -> KycStatus.VERIFIED;
        };
    }

    @Override
    public AmlResult screenAml(Transaction transaction) {
        AmlResult result = new AmlResult();
        if (transaction == null) {
            return result;
        }

        ScreeningResult screening = amlScreeningService.screen(transaction);
        if (screening == null) {
            return result;
        }

        List<String> hitLists = screening.getHitLists() != null
                ? screening.getHitLists() : new ArrayList<>();
        result.setHitLists(hitLists);
        result.setRiskScore(mapRiskLevel(screening.getRiskLevel(), hitLists));
        result.setNeedsManualReview(screening.isNeedManualReview() || !hitLists.isEmpty());

        if (screening.getMatchDetails() != null && !screening.getMatchDetails().isEmpty()) {
            result.setReason(String.join("; ", screening.getMatchDetails()));
        }

        if (Boolean.TRUE.equals(result.getNeedsManualReview())) {
            log.warn("AML screening flagged transaction {} for manual review: hits={}, score={}",
                    transaction.getTransactionId(), hitLists, result.getRiskScore());
        }
        return result;
    }

    @Override
    public void reportSuspicious(Transaction transaction, String reason) {
        if (transaction == null) {
            log.warn("reportSuspicious called with null transaction, ignored");
            return;
        }

        SuspiciousTransactionReport report = new SuspiciousTransactionReport();
        report.setTransactionDetail(buildTransactionDetail(transaction));
        report.setSuspiciousReason(reason);

        SuspiciousTransactionReport filed = amlScreeningService.fileSuspiciousReport(report);

        auditLog.recordPayment(transaction.getMerchantId(),
                transaction.getTransactionId(),
                "SAR_FILED:" + filed.getReportId(),
                null);
        log.warn("SAR filed for transaction {}: reportId={}",
                transaction.getTransactionId(), filed.getReportId());
    }

    /**
     * Map the screening risk level string to the gateway's 0-100 risk score.
     * Unknown levels fall back to a hit-count-based heuristic.
     */
    private Integer mapRiskLevel(String riskLevel, List<String> hitLists) {
        if (riskLevel == null) {
            return hitLists.isEmpty() ? 0 : 50;
        }
        return switch (riskLevel.toUpperCase()) {
            case "LOW" -> 20;
            case "MEDIUM" -> 60;
            case "HIGH" -> 90;
            case "CRITICAL" -> 100;
            default -> hitLists.isEmpty() ? 0 : 50;
        };
    }

    private String buildTransactionDetail(Transaction tx) {
        return "tx=" + tx.getTransactionId()
                + ";merchant=" + tx.getMerchantId()
                + ";amount=" + tx.getAmount()
                + ";currency=" + tx.getTokenSymbol()
                + ";from=" + tx.getFromAddress()
                + ";to=" + tx.getToAddress()
                + ";chainTx=" + tx.getChainTxHash();
    }
}

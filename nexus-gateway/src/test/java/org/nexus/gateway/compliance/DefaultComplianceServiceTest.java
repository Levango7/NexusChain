package org.nexus.gateway.compliance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.compliance.aml.AmlScreeningService;
import org.nexus.compliance.aml.ScreeningResult;
import org.nexus.compliance.aml.SuspiciousTransactionReport;
import org.nexus.compliance.kyc.KycLevel;
import org.nexus.compliance.kyc.KycService;
import org.nexus.gateway.audit.AuditLogService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultComplianceService} 单元测试：覆盖 KYC 映射、AML 筛查、
 * 可疑交易报告与各 null/异常分支。
 */
@ExtendWith(MockitoExtension.class)
class DefaultComplianceServiceTest {

    @Mock private KycService kycService;
    @Mock private AmlScreeningService amlScreeningService;
    @Mock private AuditLogService auditLog;

    private DefaultComplianceService service;

    @BeforeEach
    void setUp() {
        service = new DefaultComplianceService(kycService, amlScreeningService, auditLog);
    }

    // === checkKyc ===

    @Test
    @DisplayName("checkKyc: null/空白 userId 返回 NONE")
    void checkKyc_blank() {
        assertEquals(KycStatus.NONE, service.checkKyc(null));
        assertEquals(KycStatus.NONE, service.checkKyc("  "));
    }

    @Test
    @DisplayName("checkKyc: KycService 返回 null -> NONE")
    void checkKyc_nullLevel() {
        when(kycService.getKycStatus("u1")).thenReturn(null);
        assertEquals(KycStatus.NONE, service.checkKyc("u1"));
    }

    @Test
    @DisplayName("checkKyc: 各 KycLevel 映射正确")
    void checkKyc_mapping() {
        when(kycService.getKycStatus("none")).thenReturn(KycLevel.NONE);
        when(kycService.getKycStatus("basic")).thenReturn(KycLevel.BASIC);
        when(kycService.getKycStatus("enhanced")).thenReturn(KycLevel.ENHANCED);
        when(kycService.getKycStatus("inst")).thenReturn(KycLevel.INSTITUTIONAL);

        assertEquals(KycStatus.NONE, service.checkKyc("none"));
        assertEquals(KycStatus.BASIC, service.checkKyc("basic"));
        assertEquals(KycStatus.ENHANCED, service.checkKyc("enhanced"));
        assertEquals(KycStatus.VERIFIED, service.checkKyc("inst"));
    }

    // === screenAml ===

    @Test
    @DisplayName("screenAml: null transaction 返回空结果")
    void screenAml_null() {
        AmlResult result = service.screenAml(null);
        assertNotNull(result);
    }

    @Test
    @DisplayName("screenAml: screening 返回 null -> 空结果")
    void screenAml_nullScreening() {
        Transaction tx = sampleTx();
        when(amlScreeningService.screen(tx)).thenReturn(null);
        AmlResult result = service.screenAml(tx);
        assertNotNull(result);
    }

    @Test
    @DisplayName("screenAml: 命中黑名单 + HIGH 风险 -> needsManualReview=true, score=90")
    void screenAml_hitHighRisk() {
        Transaction tx = sampleTx();
        ScreeningResult screening = mock(ScreeningResult.class);
        when(screening.getHitLists()).thenReturn(List.of("OFAC"));
        when(screening.getRiskLevel()).thenReturn("HIGH");
        when(screening.isNeedManualReview()).thenReturn(false);
        when(screening.getMatchDetails()).thenReturn(List.of("match-1"));
        when(amlScreeningService.screen(tx)).thenReturn(screening);

        AmlResult result = service.screenAml(tx);
        assertTrue(result.getNeedsManualReview());
        assertEquals(90, result.getRiskScore());
        assertTrue(result.getHitLists().contains("OFAC"));
        assertTrue(result.getReason().contains("match-1"));
    }

    @Test
    @DisplayName("screenAml: 无命中 + LOW 风险 -> score=20")
    void screenAml_lowRisk() {
        Transaction tx = sampleTx();
        ScreeningResult screening = mock(ScreeningResult.class);
        when(screening.getHitLists()).thenReturn(List.of());
        when(screening.getRiskLevel()).thenReturn("LOW");
        when(screening.isNeedManualReview()).thenReturn(false);
        when(screening.getMatchDetails()).thenReturn(null);
        when(amlScreeningService.screen(tx)).thenReturn(screening);

        AmlResult result = service.screenAml(tx);
        assertFalse(result.getNeedsManualReview());
        assertEquals(20, result.getRiskScore());
    }

    @Test
    @DisplayName("screenAml: riskLevel=null + 无命中 -> score=0")
    void screenAml_nullRiskNoHit() {
        Transaction tx = sampleTx();
        ScreeningResult screening = mock(ScreeningResult.class);
        when(screening.getHitLists()).thenReturn(null);
        when(screening.getRiskLevel()).thenReturn(null);
        when(screening.isNeedManualReview()).thenReturn(false);
        when(screening.getMatchDetails()).thenReturn(null);
        when(amlScreeningService.screen(tx)).thenReturn(screening);

        AmlResult result = service.screenAml(tx);
        assertEquals(0, result.getRiskScore());
    }

    @Test
    @DisplayName("screenAml: MEDIUM/CRITICAL/未知 riskLevel 映射")
    void screenAml_otherRiskLevels() {
        for (String[] row : new String[][]{
                {"MEDIUM", "60"},
                {"CRITICAL", "100"},
                {"UNKNOWN", "0"}
        }) {
            Transaction tx = sampleTx();
            ScreeningResult screening = mock(ScreeningResult.class);
            when(screening.getHitLists()).thenReturn(List.of());
            when(screening.getRiskLevel()).thenReturn(row[0]);
            when(screening.isNeedManualReview()).thenReturn(false);
            when(screening.getMatchDetails()).thenReturn(null);
            when(amlScreeningService.screen(tx)).thenReturn(screening);

            AmlResult result = service.screenAml(tx);
            assertEquals(Integer.parseInt(row[1]), result.getRiskScore());
        }
    }

    @Test
    @DisplayName("screenAml: 未知 riskLevel + 有命中 -> score=50")
    void screenAml_unknownRiskWithHit() {
        Transaction tx = sampleTx();
        ScreeningResult screening = mock(ScreeningResult.class);
        when(screening.getHitLists()).thenReturn(List.of("EU"));
        when(screening.getRiskLevel()).thenReturn("UNKNOWN");
        when(screening.isNeedManualReview()).thenReturn(false);
        when(screening.getMatchDetails()).thenReturn(null);
        when(amlScreeningService.screen(tx)).thenReturn(screening);

        AmlResult result = service.screenAml(tx);
        assertEquals(50, result.getRiskScore());
    }

    // === reportSuspicious ===

    @Test
    @DisplayName("reportSuspicious: null transaction 不抛异常")
    void reportSuspicious_null() {
        service.reportSuspicious(null, "reason");
        verify(amlScreeningService, never()).fileSuspiciousReport(any());
    }

    @Test
    @DisplayName("reportSuspicious: 正常提交 SAR 并写审计")
    void reportSuspicious_normal() {
        Transaction tx = sampleTx();
        SuspiciousTransactionReport filed = mock(SuspiciousTransactionReport.class);
        when(filed.getReportId()).thenReturn("SAR-001");
        when(amlScreeningService.fileSuspiciousReport(any())).thenReturn(filed);

        service.reportSuspicious(tx, "suspicious pattern");

        verify(auditLog).recordPayment(eq(100L), eq("TX-001"), contains("SAR_FILED:"), isNull());
    }

    private Transaction sampleTx() {
        Transaction tx = new Transaction();
        tx.setTransactionId("TX-001");
        tx.setMerchantId(100L);
        tx.setAmount(java.math.BigDecimal.TEN);
        tx.setTokenSymbol("NEX");
        tx.setFromAddress("0xFrom");
        tx.setToAddress("0xTo");
        tx.setChainTxHash("0xChainTx");
        return tx;
    }
}
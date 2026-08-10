package org.nexus.compliance.kyc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DefaultKycService} 单元测试。
 */
class DefaultKycServiceTest {

    private DefaultKycService service;

    @BeforeEach
    void setUp() {
        service = new DefaultKycService();
    }

    @Test
    void submitKyc_validApplication_shouldBePending() {
        KycApplication app = application("U1", "ID_CARD", "110101199001011234", null);

        KycApplication submitted = service.submitKyc(app);

        assertEquals(KycApplication.ApplicationStatus.PENDING, submitted.getStatus());
        assertEquals("KYC-", submitted.getApplicationId().substring(0, 4));
    }

    @Test
    void submitKyc_missingUserId_shouldThrow() {
        KycApplication app = application(null, "ID_CARD", "123", null);
        assertThrows(IllegalArgumentException.class, () -> service.submitKyc(app));
    }

    @Test
    void submitKyc_duplicatePending_shouldThrow() {
        service.submitKyc(application("U1", "ID_CARD", "123", null));
        assertThrows(IllegalStateException.class,
                () -> service.submitKyc(application("U1", "PASSPORT", "456", null)));
    }

    @Test
    void reviewKyc_withIdImage_shouldApprove() {
        KycApplication app = service.submitKyc(
                application("U1", "ID_CARD", "123", "https://img/1.jpg"));

        KycApplication reviewed = service.reviewKyc(app.getApplicationId());

        assertEquals(KycApplication.ApplicationStatus.APPROVED, reviewed.getStatus());
    }

    @Test
    void reviewKyc_missingIdImage_shouldReject() {
        KycApplication app = service.submitKyc(application("U1", "ID_CARD", "123", null));

        KycApplication reviewed = service.reviewKyc(app.getApplicationId());

        assertEquals(KycApplication.ApplicationStatus.REJECTED, reviewed.getStatus());
    }

    @Test
    void getKycStatus_approvedWithImage_shouldBeEnhanced() {
        KycApplication app = service.submitKyc(
                application("U1", "ID_CARD", "123", "https://img/1.jpg"));
        service.reviewKyc(app.getApplicationId());

        assertEquals(KycLevel.ENHANCED, service.getKycStatus("U1"));
    }

    @Test
    void getKycStatus_institutional_shouldBeInstitutional() {
        KycApplication app = service.submitKyc(
                application("U2", "INSTITUTIONAL", "LIC-001", "https://img/lic.jpg"));
        service.reviewKyc(app.getApplicationId());

        assertEquals(KycLevel.INSTITUTIONAL, service.getKycStatus("U2"));
    }

    @Test
    void getKycStatus_noApplication_shouldBeNone() {
        assertEquals(KycLevel.NONE, service.getKycStatus("U_UNKNOWN"));
    }

    private KycApplication application(String userId, String idType, String idNumber, String imageUrl) {
        KycApplication app = new KycApplication();
        app.setUserId(userId);
        app.setIdType(idType);
        app.setIdNumber(idNumber);
        app.setIdImageUrl(imageUrl);
        return app;
    }
}

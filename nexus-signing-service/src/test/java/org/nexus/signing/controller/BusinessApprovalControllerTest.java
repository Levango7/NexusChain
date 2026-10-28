package org.nexus.signing.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.sdk.signing.BusinessApprovalPayloads;
import org.nexus.signing.business.BusinessApprovalService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2 登记端点测试（薄映射层）：200 成功 / 400 参数非法（5004）/ 409 哈希冲突（5002）。
 * 服务逻辑在 {@code BusinessApprovalServiceTest}；此处只钉 HTTP 口径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessApprovalControllerTest {

    private static final String BA_ID = "WD-a2-0001";
    private static final String FROM = "0xplatform0000000000000000000000deadbeef";
    private static final String TO_HASH = "0xrecipienthash0011";
    private static final BigDecimal AMOUNT = new BigDecimal("50000");

    @Mock
    private BusinessApprovalService businessApprovalService;

    private BusinessApprovalController controller;

    @BeforeEach
    void setUp() {
        controller = new BusinessApprovalController(businessApprovalService);
    }

    private static String validExpiry() {
        return Instant.now().plusSeconds(3600).toString();
    }

    @Test
    @DisplayName("登记成功 → 200 + statusCode 2000 + data 透传")
    void register_success() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("businessApprovalId", BA_ID);
        data.put("state", "REGISTERED");
        data.put("idempotent", Boolean.FALSE);
        when(businessApprovalService.register(any())).thenReturn(data);

        var response = controller.register(BA_ID,
                BusinessApprovalPayloads.canonicalHash(FROM, TO_HASH, AMOUNT, BA_ID),
                AMOUNT, "USDT", FROM, TO_HASH, validExpiry(), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2000, response.getBody().get("statusCode"));
        assertEquals(data, response.getBody().get("data"));
    }

    @Test
    @DisplayName("expiresAt 不可解析 → 400 + 5004，服务不被触达")
    void register_badExpiry() {
        var response = controller.register(BA_ID, "0".repeat(64), AMOUNT,
                "USDT", FROM, TO_HASH, "not-an-instant", null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(5004, response.getBody().get("statusCode"));
        verify(businessApprovalService, never()).register(any());
    }

    @Test
    @DisplayName("服务抛 IAE（参数非法）→ 400 + 5004")
    void register_illegalArgument() {
        when(businessApprovalService.register(any()))
                .thenThrow(new IllegalArgumentException("amount must be positive"));

        var response = controller.register(BA_ID, "0".repeat(64), AMOUNT,
                "USDT", FROM, TO_HASH, validExpiry(), null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(5004, response.getBody().get("statusCode"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("amount"));
    }

    @Test
    @DisplayName("哈希冲突 → 409 + 5002")
    void register_hashConflict() {
        when(businessApprovalService.register(any()))
                .thenThrow(new BusinessApprovalService.PayloadHashConflictException(
                        "businessApprovalId already registered with a different payloadHash"));

        var response = controller.register(BA_ID, "0".repeat(64), AMOUNT,
                "USDT", FROM, TO_HASH, validExpiry(), null);

        assertEquals(409, response.getStatusCode().value());
        assertEquals(5002, response.getBody().get("statusCode"));
    }
}

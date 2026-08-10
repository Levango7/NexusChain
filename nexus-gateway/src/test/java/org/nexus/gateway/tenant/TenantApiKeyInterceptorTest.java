package org.nexus.gateway.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link TenantApiKeyInterceptor} 单元测试（P4-T6 多租户改造）。
 *
 * <p>覆盖有效/无效/缺失 API Key 场景，以及多租户功能开关、TenantContext 清理。</p>
 */
@ExtendWith(MockitoExtension.class)
class TenantApiKeyInterceptorTest {

    @Mock
    private TenantService tenantService;

    private TenantApiKeyInterceptor interceptor;
    private TenantApiKeyInterceptor disabledInterceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TenantApiKeyInterceptor(tenantService, true);
        disabledInterceptor = new TenantApiKeyInterceptor(tenantService, false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Tenant activeTenant(String tenantId, String apiKey) {
        Tenant t = new Tenant();
        t.setTenantId(tenantId);
        t.setApiKey(apiKey);
        t.setStatus(TenantStatus.ACTIVE);
        t.setConfig(new TenantConfig());
        return t;
    }

    @Test
    @DisplayName("有效 API Key：放行并填充 TenantContext")
    void validApiKeyPassesAndSetsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantApiKeyInterceptor.TENANT_API_KEY_HEADER, "valid-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Tenant tenant = activeTenant("t-1", "valid-key");
        when(tenantService.validateApiKey("valid-key")).thenReturn(Optional.of(tenant));

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        assertEquals(200, response.getStatus());
        assertEquals("t-1", request.getAttribute(TenantApiKeyInterceptor.TENANT_ID_ATTR));
        assertEquals("t-1", TenantContext.getCurrentTenantId());
    }

    @Test
    @DisplayName("无效 API Key：返回 401")
    void invalidApiKeyReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantApiKeyInterceptor.TENANT_API_KEY_HEADER, "invalid-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(tenantService.validateApiKey("invalid-key")).thenReturn(Optional.empty());

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
        assertNull(TenantContext.getCurrentTenantId());
    }

    @Test
    @DisplayName("缺失 API Key：向后兼容放行（不设置 TenantContext）")
    void missingApiKeyPassesForBackwardCompat() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        assertEquals(200, response.getStatus());
        assertNull(TenantContext.getCurrentTenantId());
    }

    @Test
    @DisplayName("空 API Key：向后兼容放行")
    void emptyApiKeyPassesForBackwardCompat() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantApiKeyInterceptor.TENANT_API_KEY_HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        assertNull(TenantContext.getCurrentTenantId());
    }

    @Test
    @DisplayName("多租户禁用时：直接放行")
    void disabledTenantSkipsAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantApiKeyInterceptor.TENANT_API_KEY_HEADER, "any-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = disabledInterceptor.preHandle(request, response, null);

        assertTrue(result);
        assertNull(TenantContext.getCurrentTenantId());
        verifyNoInteractions(tenantService);
    }

    @Test
    @DisplayName("afterCompletion：清除 TenantContext")
    void afterCompletionClearsContext() throws Exception {
        TenantContext.setCurrentTenantId("t-1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.afterCompletion(request, response, null, null);

        assertNull(TenantContext.getCurrentTenantId());
    }

    @Test
    @DisplayName("SUSPENDED 租户的 API Key：返回 401")
    void suspendedTenantApiKeyReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantApiKeyInterceptor.TENANT_API_KEY_HEADER, "suspended-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // validateApiKey 对 SUSPENDED 租户返回 empty
        when(tenantService.validateApiKey("suspended-key")).thenReturn(Optional.empty());

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
    }
}
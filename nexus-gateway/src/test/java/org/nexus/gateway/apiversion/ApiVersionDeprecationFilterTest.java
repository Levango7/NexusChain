package org.nexus.gateway.apiversion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ApiVersionDeprecationFilter 单元测试。
 *
 * <p>测试过滤器对不同版本状态的行为：</p>
 * <ul>
 *   <li>ACTIVE — 正常继续处理</li>
 *   <li>DEPRECATED — 添加通知头后继续处理</li>
 *   <li>SUNSET — 返回 410 Gone</li>
 *   <li>RETIRED — 返回 404 Not Found</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ApiVersionDeprecationFilterTest {

    @Mock
    private ApiVersionDeprecationService deprecationService;

    @Mock
    private FilterChain filterChain;

    private ApiVersionDeprecationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiVersionDeprecationFilter(deprecationService);
    }

    // === ACTIVE 版本测试 ===

    @Test
    @DisplayName("ACTIVE 版本：正常继续处理，不添加废弃头")
    void testActiveVersionPassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(deprecationService.checkVersionStatus("v2"))
                .thenReturn(ApiVersionPolicy.VersionStatus.ACTIVE);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Deprecation"));
    }

    // === DEPRECATED 版本测试 ===

    @Test
    @DisplayName("DEPRECATED 版本：添加废弃通知头后继续处理")
    void testDeprecatedVersionAddsHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(deprecationService.checkVersionStatus("v1"))
                .thenReturn(ApiVersionPolicy.VersionStatus.DEPRECATED);
        when(deprecationService.getDeprecationHeaders("v1"))
                .thenReturn(Map.of(
                        "Deprecation", "true",
                        "Sunset", "2027-02-09",
                        "Link", "</api/v2/orders>; rel=\"successor-version\""));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals("true", response.getHeader("Deprecation"));
        assertEquals("2027-02-09", response.getHeader("Sunset"));
        assertEquals("</api/v2/orders>; rel=\"successor-version\"", response.getHeader("Link"));
    }

    // === SUNSET 版本测试 ===

    @Test
    @DisplayName("SUNSET 版本：返回 410 Gone + 迁移指南")
    void testSunsetVersionReturns410() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.SUNSET);
        policy.setSuccessorVersion("v2");
        policy.setMigrationGuide("Please migrate to v2");

        when(deprecationService.checkVersionStatus("v1"))
                .thenReturn(ApiVersionPolicy.VersionStatus.SUNSET);
        when(deprecationService.getPolicy("v1"))
                .thenReturn(Optional.of(policy));
        when(deprecationService.getDeprecationHeaders("v1"))
                .thenReturn(Map.of("Deprecation", "true", "Sunset", "2027-02-09"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(410, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("VERSION_SUNSET"));
        assertTrue(body.contains("v2"));
        assertTrue(body.contains("Please migrate to v2"));
    }

    @Test
    @DisplayName("SUNSET 版本：无后继版本时仍返回 410")
    void testSunsetVersionNoSuccessor() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.SUNSET);
        policy.setMigrationGuide(null);

        when(deprecationService.checkVersionStatus("v1"))
                .thenReturn(ApiVersionPolicy.VersionStatus.SUNSET);
        when(deprecationService.getPolicy("v1"))
                .thenReturn(Optional.of(policy));
        when(deprecationService.getDeprecationHeaders("v1"))
                .thenReturn(Map.of("Deprecation", "true"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(410, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("VERSION_SUNSET"));
    }

    // === RETIRED 版本测试 ===

    @Test
    @DisplayName("RETIRED 版本：返回 404 Not Found")
    void testRetiredVersionReturns404() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v0/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(deprecationService.checkVersionStatus("v0"))
                .thenReturn(ApiVersionPolicy.VersionStatus.RETIRED);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(404, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("VERSION_RETIRED"));
        assertTrue(body.contains("v0"));
    }

    // === 版本治理 API 自身路径不拦截 ===

    @Test
    @DisplayName("版本治理 API 路径不被拦截")
    void testGovernanceApiPathNotFiltered() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/api-version/policies");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(deprecationService, never()).checkVersionStatus(any());
    }

    // === 无版本标识的请求 ===

    @Test
    @DisplayName("无版本标识的请求正常通过")
    void testNoVersionLabelPassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(deprecationService, never()).checkVersionStatus(any());
    }

    // === Header 版本协商 ===

    @Test
    @DisplayName("Header 版本协商：从 X-NexusChain-API-Version 头提取版本")
    void testHeaderVersionNegotiation() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader("X-NexusChain-API-Version", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(deprecationService.checkVersionStatus("v1"))
                .thenReturn(ApiVersionPolicy.VersionStatus.DEPRECATED);
        when(deprecationService.getDeprecationHeaders("v1"))
                .thenReturn(Map.of("Deprecation", "true"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals("true", response.getHeader("Deprecation"));
    }

    // === 辅助方法 ===

    private ApiVersionPolicy createPolicy(String version, ApiVersionPolicy.VersionStatus status) {
        ApiVersionPolicy policy = new ApiVersionPolicy();
        policy.setId(1L);
        policy.setVersion(version);
        policy.setStatus(status);
        policy.setSunsetAt(LocalDate.of(2027, 2, 9));
        return policy;
    }
}
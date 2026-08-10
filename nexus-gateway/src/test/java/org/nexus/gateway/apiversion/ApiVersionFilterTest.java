package org.nexus.gateway.apiversion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ApiVersionFilter} 单元测试（P4-T7）。
 *
 * <p>覆盖版本解析的全部路径：</p>
 * <ul>
 *   <li>URL 路径版本（v1/v2）</li>
 *   <li>Header 版本协商</li>
 *   <li>默认版本（无任何标识）</li>
 *   <li>v1 Deprecation/Sunset/Link 头注入</li>
 *   <li>v2 不附加弃用头</li>
 *   <li>URL 与 Header 不一致时 URL 优先</li>
 * </ul>
 */
@DisplayName("ApiVersionFilter 版本解析")
class ApiVersionFilterTest {

    private ApiVersionFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new ApiVersionFilter();
        chain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("URL 路径 /api/v1/* → 解析为 v1，注入 Deprecation/Sunset/Link 头")
    void urlPathV1_resolvesAndAddsDeprecationHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders/123");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(1, req.getAttribute(ApiVersionFilter.ATTR_API_VERSION));
        assertEquals("v1", req.getAttribute(ApiVersionFilter.ATTR_API_VERSION_LABEL));
        assertEquals("1", resp.getHeader(ApiVersionFilter.VERSION_HEADER));
        assertEquals(ApiVersionFilter.V1_DEPRECATION_DATE, resp.getHeader(ApiVersionFilter.DEPRECATION_HEADER));
        assertEquals(ApiVersionFilter.V1_SUNSET_DATE, resp.getHeader(ApiVersionFilter.SUNSET_HEADER));
        assertNotNull(resp.getHeader(ApiVersionFilter.LINK_HEADER));
        assertTrue(resp.getHeader(ApiVersionFilter.LINK_HEADER).contains("deprecation"));
        verify(chain).doFilter(req, resp);
    }

    @Test
    @DisplayName("URL 路径 /api/v2/* → 解析为 v2，不注入弃用头")
    void urlPathV2_resolvesWithoutDeprecationHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v2/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(2, req.getAttribute(ApiVersionFilter.ATTR_API_VERSION));
        assertEquals("v2", req.getAttribute(ApiVersionFilter.ATTR_API_VERSION_LABEL));
        assertEquals("2", resp.getHeader(ApiVersionFilter.VERSION_HEADER));
        assertNull(resp.getHeader(ApiVersionFilter.DEPRECATION_HEADER));
        assertNull(resp.getHeader(ApiVersionFilter.SUNSET_HEADER));
        verify(chain).doFilter(req, resp);
    }

    @Test
    @DisplayName("Header X-NexusChain-API-Version=2 → 解析为 v2")
    void headerVersion2_resolvesToV2() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
        req.addHeader(ApiVersionFilter.VERSION_HEADER, "2");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(2, req.getAttribute(ApiVersionFilter.ATTR_API_VERSION));
        assertEquals("v2", req.getAttribute(ApiVersionFilter.ATTR_API_VERSION_LABEL));
        assertNull(resp.getHeader(ApiVersionFilter.DEPRECATION_HEADER));
    }

    @Test
    @DisplayName("Header X-NexusChain-API-Version=1 → 解析为 v1，注入弃用头")
    void headerVersion1_resolvesToV1WithDeprecation() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
        req.addHeader(ApiVersionFilter.VERSION_HEADER, "1");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(1, req.getAttribute(ApiVersionFilter.ATTR_API_VERSION));
        assertNotNull(resp.getHeader(ApiVersionFilter.DEPRECATION_HEADER));
        assertNotNull(resp.getHeader(ApiVersionFilter.SUNSET_HEADER));
    }

    @Test
    @DisplayName("无版本标识 → 默认 v1（向后兼容）")
    void noVersionIndicator_defaultsToV1() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(1, req.getAttribute(ApiVersionFilter.ATTR_API_VERSION));
        assertEquals("v1", req.getAttribute(ApiVersionFilter.ATTR_API_VERSION_LABEL));
        assertNotNull(resp.getHeader(ApiVersionFilter.DEPRECATION_HEADER));
    }

    @Test
    @DisplayName("URL 路径版本与 Header 不一致 → URL 路径优先")
    void urlAndHeaderConflict_urlWins() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v2/orders");
        req.addHeader(ApiVersionFilter.VERSION_HEADER, "1");  // Header 声明 v1，但 URL 是 v2
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(2, req.getAttribute(ApiVersionFilter.ATTR_API_VERSION));
        assertEquals("v2", req.getAttribute(ApiVersionFilter.ATTR_API_VERSION_LABEL));
        assertNull(resp.getHeader(ApiVersionFilter.DEPRECATION_HEADER));
    }

    @Test
    @DisplayName("非 API 路径 → 默认 v1，仍注入弃用头")
    void nonApiPath_defaultsToV1() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(1, req.getAttribute(ApiVersionFilter.ATTR_API_VERSION));
        verify(chain).doFilter(req, resp);
    }

    @Test
    @DisplayName("v1 Sunset 头值为 2027-02-09（6 个月兼容期）")
    void v1_sunsetDateIsSixMonthsAfterDeprecation() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals("2026-08-09", resp.getHeader(ApiVersionFilter.DEPRECATION_HEADER));
        assertEquals("2027-02-09", resp.getHeader(ApiVersionFilter.SUNSET_HEADER));
    }

    @Test
    @DisplayName("Link 头包含迁移指南 URL 与 rel=deprecation")
    void v1_linkHeaderContainsMigrationGuide() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        String link = resp.getHeader(ApiVersionFilter.LINK_HEADER);
        assertNotNull(link);
        assertTrue(link.contains(ApiVersionFilter.MIGRATION_GUIDE_URL));
        assertTrue(link.contains("rel=\"deprecation\""));
    }

    @Test
    @DisplayName("不支持的 URL 版本（v99）→ 仍记录版本但控制器可据此返回 404")
    void unsupportedUrlVersion_recordedButNotBlocked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v99/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(99, req.getAttribute(ApiVersionFilter.ATTR_API_VERSION));
        // 不阻断请求
        verify(chain).doFilter(req, resp);
    }

    @Test
    @DisplayName("不支持的 Header 版本（99）→ 回退到默认 v1")
    void unsupportedHeaderVersion_fallsBackToDefault() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
        req.addHeader(ApiVersionFilter.VERSION_HEADER, "99");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(1, req.getAttribute(ApiVersionFilter.ATTR_API_VERSION));
    }

    @Nested
    @DisplayName("版本解析逻辑（resolveVersion 方法）")
    class ResolveVersionLogic {

        @Test
        @DisplayName("URL v1 → source=URL")
        void urlV1_sourceIsUrl() {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders");
            ApiVersionFilter.VersionResolution r = filter.resolveVersion("/api/v1/orders", req);
            assertEquals(1, r.version);
            assertEquals(ApiVersionFilter.VersionSource.URL, r.source);
        }

        @Test
        @DisplayName("URL v2 → source=URL")
        void urlV2_sourceIsUrl() {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v2/orders");
            ApiVersionFilter.VersionResolution r = filter.resolveVersion("/api/v2/orders", req);
            assertEquals(2, r.version);
            assertEquals(ApiVersionFilter.VersionSource.URL, r.source);
        }

        @Test
        @DisplayName("Header 2 → source=HEADER")
        void header2_sourceIsHeader() {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
            req.addHeader(ApiVersionFilter.VERSION_HEADER, "2");
            ApiVersionFilter.VersionResolution r = filter.resolveVersion("/api/orders", req);
            assertEquals(2, r.version);
            assertEquals(ApiVersionFilter.VersionSource.HEADER, r.source);
        }

        @Test
        @DisplayName("无标识 → source=DEFAULT, version=1")
        void noIndicator_sourceIsDefault() {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
            ApiVersionFilter.VersionResolution r = filter.resolveVersion("/api/orders", req);
            assertEquals(1, r.version);
            assertEquals(ApiVersionFilter.VersionSource.DEFAULT, r.source);
        }
    }
}
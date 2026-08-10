package org.nexus.gateway.apiversion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 版本解析过滤器（P4-T7）。
 *
 * <p>负责在请求链最早期解析 API 版本，支持两种协商方式：</p>
 * <ol>
 *   <li><b>URL 路径版本</b>：{@code /api/v1/*} 与 {@code /api/v2/*} 并存（优先级最高）</li>
 *   <li><b>Header 版本协商</b>：{@code X-NexusChain-API-Version: 1} 或 {@code 2}</li>
 * </ol>
 *
 * <p>解析结果以请求属性 {@code nexus.apiVersion}（数值整数 1/2）与 {@code nexus.apiVersionLabel}
 * （"v1"/"v2"）形式暴露给下游拦截器与控制器。</p>
 *
 * <p>对 v1 端点附加 RFC 8594 Sunset 与 RFC 7234 Deprecation 头声明弃用：
 * v1 兼容期 6 个月（2026-08-09 发布，2027-02-09 Sunset）。</p>
 *
 * <p>本过滤器仅做解析与头注入，不阻断请求；URL 路径与 Header 不一致时以 URL 路径为准
 * （路径版本是资源身份的一部分，Header 仅作协商提示）。</p>
 */
@Component
@Order(-200)  // 早于 ApiKeyInterceptor / RateLimiter / RequestSignatureInterceptor
public class ApiVersionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionFilter.class);

    /** Header 名：API 版本协商 */
    public static final String VERSION_HEADER = "X-NexusChain-API-Version";

    /** 请求属性键：解析后的数值版本（Integer 1 或 2） */
    public static final String ATTR_API_VERSION = "nexus.apiVersion";

    /** 请求属性键：解析后的标签版本（"v1" 或 "v2"） */
    public static final String ATTR_API_VERSION_LABEL = "nexus.apiVersionLabel";

    /** Sunset 头名（RFC 8594） */
    public static final String SUNSET_HEADER = "Sunset";

    /** Deprecation 头名（RFC 7234） */
    public static final String DEPRECATION_HEADER = "Deprecation";

    /** Link 头名（迁移指南） */
    public static final String LINK_HEADER = "Link";

    /** 当前最新稳定版本（数值） */
    public static final int CURRENT_VERSION = 2;

    /** 仍受支持的最低版本（数值） */
    public static final int MIN_SUPPORTED_VERSION = 1;

    /** 仍受支持的最高版本（数值） */
    public static final int MAX_SUPPORTED_VERSION = 2;

    /** v1 弃用声明日期（ISO-8601 日期） */
    public static final String V1_DEPRECATION_DATE = "2026-08-09";

    /** v1 Sunset 失效日期（ISO-8601 日期，弃用后 6 个月） */
    public static final String V1_SUNSET_DATE = "2027-02-09";

    /** v2 迁移指南 URL */
    public static final String MIGRATION_GUIDE_URL =
            "https://docs.nexus.network/api/v2-migration-guide";

    /** URL 路径版本提取正则：/api/vN/... */
    private static final Pattern URL_VERSION_PATTERN = Pattern.compile("^/api/v(\\d+)/");

    /** Header 版本正则：纯数字 1-9 */
    private static final Pattern HEADER_VERSION_PATTERN = Pattern.compile("^[1-9]\\d*$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        VersionResolution resolution = resolveVersion(path, request);

        // 暴露解析结果给下游
        request.setAttribute(ATTR_API_VERSION, resolution.version);
        request.setAttribute(ATTR_API_VERSION_LABEL, resolution.label);

        // 响应头声明本次使用的版本
        response.setHeader(VERSION_HEADER, String.valueOf(resolution.version));

        // v1 端点附加 Deprecation + Sunset + Link 头
        if (resolution.version == 1) {
            applyDeprecationHeaders(response);
        }

        if (resolution.source == VersionSource.URL) {
            log.debug("API version resolved from URL path: {} -> v{}", path, resolution.version);
        } else if (resolution.source == VersionSource.HEADER) {
            log.debug("API version resolved from header: v{} (path={})", resolution.version, path);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 解析请求版本。优先级：URL 路径 > Header > 默认（v1）。
     *
     * @param path    请求 URI
     * @param request HTTP 请求（用于读取 Header）
     * @return 版本解析结果
     */
    VersionResolution resolveVersion(String path, HttpServletRequest request) {
        // 1. URL 路径版本：/api/v1/... 或 /api/v2/...
        if (path != null) {
            Matcher m = URL_VERSION_PATTERN.matcher(path);
            if (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (v >= MIN_SUPPORTED_VERSION && v <= MAX_SUPPORTED_VERSION) {
                    return new VersionResolution(v, "v" + v, VersionSource.URL);
                }
                // URL 中出现不支持的版本：交给控制器返回 404，但为保持过滤器不阻断，
                // 仍记录该数值（控制器可据此返回明确错误）
                return new VersionResolution(v, "v" + v, VersionSource.URL);
            }
        }

        // 2. Header 版本协商
        String headerValue = request.getHeader(VERSION_HEADER);
        if (headerValue != null && HEADER_VERSION_PATTERN.matcher(headerValue).matches()) {
            int v = Integer.parseInt(headerValue);
            if (v >= MIN_SUPPORTED_VERSION && v <= MAX_SUPPORTED_VERSION) {
                return new VersionResolution(v, "v" + v, VersionSource.HEADER);
            }
        }

        // 3. 默认：v1（保持向后兼容——未带版本标识的请求按 v1 处理）
        return new VersionResolution(1, "v1", VersionSource.DEFAULT);
    }

    /**
     * 为 v1 响应附加 Deprecation / Sunset / Link 头。
     *
     * <p>遵循 RFC 7234（Deprecation）与 RFC 8594（Sunset）：</p>
     * <ul>
     *   <li>{@code Deprecation: 2026-08-09} —— 弃用声明日期</li>
     *   <li>{@code Sunset: 2027-02-09} —— 计划移除日期（6 个月兼容期）</li>
     *   <li>{@code Link: <https://docs.nexus.network/api/v2-migration-guide>; rel="deprecation"</li>
     * </ul>
     */
    private void applyDeprecationHeaders(HttpServletResponse response) {
        response.setHeader(DEPRECATION_HEADER, V1_DEPRECATION_DATE);
        response.setHeader(SUNSET_HEADER, V1_SUNSET_DATE);
        response.addHeader(LINK_HEADER,
                "<" + MIGRATION_GUIDE_URL + ">; rel=\"deprecation\"; type=\"text/html\"");
    }

    /** 版本来源标识 */
    enum VersionSource {
        URL,     // 从 URL 路径解析
        HEADER,  // 从 Header 解析
        DEFAULT  // 默认值（v1）
    }

    /** 版本解析结果 */
    static final class VersionResolution {
        final int version;          // 数值版本：1 或 2
        final String label;         // 标签：v1 或 v2
        final VersionSource source; // 解析来源

        VersionResolution(int version, String label, VersionSource source) {
            this.version = version;
            this.label = label;
            this.source = source;
        }
    }
}
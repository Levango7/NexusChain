package org.nexus.gateway.apiversion;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 版本废弃通知过滤器。
 *
 * <p>在请求链中对每个 API 请求执行版本治理检查：</p>
 * <ol>
 *   <li>从 URL 路径或 Header 中提取 API 版本</li>
 *   <li>查询版本策略（{@link ApiVersionDeprecationService}）</li>
 *   <li>根据版本状态采取不同行为：</li>
 *   <li>   — {@code DEPRECATED}：添加 Deprecation + Sunset + Link 头到响应，继续处理</li>
 *   <li>   — {@code SUNSET}（已过 Sunset 日期）：返回 410 Gone + 迁移指南</li>
 *   <li>   — {@code RETIRED}：返回 404 Not Found</li>
 *   <li>   — {@code ACTIVE}：正常继续处理</li>
 * </ol>
 *
 * <p>此过滤器不阻断 DEPRECATED 版本的请求（仅添加通知头），
 * 但会阻断 SUNSET 和 RETIRED 版本的请求。</p>
 *
 * <p>运行顺序：晚于 {@link ApiVersionFilter}（-200），早于业务拦截器。</p>
 */
@Component
@Order(-150)
public class ApiVersionDeprecationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionDeprecationFilter.class);

    /** URL 路径版本提取正则：/api/vN/... */
    private static final Pattern URL_VERSION_PATTERN = Pattern.compile("^/api/v(\\d+)/");

    private final ApiVersionDeprecationService deprecationService;

    public ApiVersionDeprecationFilter(ApiVersionDeprecationService deprecationService) {
        this.deprecationService = deprecationService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();
        String versionLabel = extractVersionLabel(path, httpRequest);

        // 版本治理 API 自身路径不拦截
        if (path != null && path.startsWith("/api/v1/api-version/")) {
            chain.doFilter(request, response);
            return;
        }

        if (versionLabel == null) {
            chain.doFilter(request, response);
            return;
        }

        ApiVersionPolicy.VersionStatus status = deprecationService.checkVersionStatus(versionLabel);

        switch (status) {
            case ACTIVE:
                // 正常版本，继续处理
                chain.doFilter(request, response);
                break;

            case DEPRECATED:
                // 废弃版本：添加通知头，继续处理请求
                applyDeprecationHeaders(httpResponse, versionLabel);
                chain.doFilter(request, response);
                break;

            case SUNSET:
                // 日落版本：返回 410 Gone + 迁移指南
                handleSunset(httpResponse, versionLabel);
                break;

            case RETIRED:
                // 退役版本：返回 404 Not Found
                handleRetired(httpResponse, versionLabel);
                break;

            default:
                chain.doFilter(request, response);
                break;
        }
    }

    /**
     * 从 URL 路径或 Header 中提取版本标签。
     *
     * @param path    请求 URI
     * @param request HTTP 请求
     * @return 版本标签（如 "v1"），或 null（无法识别）
     */
    private String extractVersionLabel(String path, HttpServletRequest request) {
        // 1. URL 路径版本
        if (path != null) {
            Matcher m = URL_VERSION_PATTERN.matcher(path);
            if (m.find()) {
                return "v" + m.group(1);
            }
        }
        // 2. Header 版本协商
        String headerValue = request.getHeader(ApiVersionFilter.VERSION_HEADER);
        if (headerValue != null && headerValue.matches("^[1-9]\\d*$")) {
            return "v" + headerValue;
        }
        return null;
    }

    /**
     * 为 DEPRECATED 版本响应添加废弃通知头。
     */
    private void applyDeprecationHeaders(HttpServletResponse response, String versionLabel) {
        Map<String, String> headers = deprecationService.getDeprecationHeaders(versionLabel);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            response.setHeader(entry.getKey(), entry.getValue());
        }
        log.debug("Deprecation headers applied for version {}", versionLabel);
    }

    /**
     * 处理 SUNSET 版本——返回 410 Gone + 迁移指南。
     */
    private void handleSunset(HttpServletResponse response, String versionLabel) throws IOException {
        response.setStatus(HttpServletResponse.SC_GONE);
        response.setContentType("application/json;charset=UTF-8");

        Optional<ApiVersionPolicy> opt = deprecationService.getPolicy(versionLabel);
        String migrationGuide = opt.map(ApiVersionPolicy::getMigrationGuide).orElse(null);
        String successorVersion = opt.map(ApiVersionPolicy::getSuccessorVersion).orElse(null);

        // 添加 Deprecation 头
        Map<String, String> headers = deprecationService.getDeprecationHeaders(versionLabel);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            response.setHeader(entry.getKey(), entry.getValue());
        }

        // 构造 410 响应体
        StringBuilder body = new StringBuilder();
        body.append("{\"error\":{\"code\":\"VERSION_SUNSET\",\"message\":\"API version ")
                .append(versionLabel)
                .append(" has been sunset and is no longer available\"");
        if (successorVersion != null) {
            body.append(",\"details\":{\"successorVersion\":\"")
                    .append(successorVersion)
                    .append("\"");
            if (migrationGuide != null) {
                body.append(",\"migrationGuide\":\"")
                        .append(escapeJson(migrationGuide))
                        .append("\"");
            }
            body.append("}");
        }
        body.append("}}");

        response.getWriter().write(body.toString());
        log.warn("Request to sunset version {} rejected with 410 Gone", versionLabel);
    }

    /**
     * 处理 RETIRED 版本——返回 404 Not Found。
     */
    private void handleRetired(HttpServletResponse response, String versionLabel) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json;charset=UTF-8");

        String body = "{\"error\":{\"code\":\"VERSION_RETIRED\",\"message\":\"API version "
                + versionLabel + " has been retired and is no longer available\"}}";

        response.getWriter().write(body.toString());
        log.warn("Request to retired version {} rejected with 404 Not Found", versionLabel);
    }

    /**
     * 简单 JSON 字符串转义。
     */
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
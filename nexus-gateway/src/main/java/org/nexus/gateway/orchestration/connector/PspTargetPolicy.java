package org.nexus.gateway.orchestration.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 动态 PSP 连接器目标地址与凭据引用策略（P0，2026-09-17）。
 *
 * <p>背景：{@code POST /api/v1/payments/connectors} 原实现把请求体里的
 * {@code api_key_env} 与 {@code base_url} 直接交给 DynamicHttpPspConnector：
 * 前者经 {@code System.getenv(任意变量名)} 解析后以
 * {@code Authorization: Bearer} 发往后者。该端点无角色校验，任意已认证商户
 * 即可读取并外传任意环境变量（数据库口令、签名密钥、云凭证），并可让网关
 * 向内网 / 云元数据地址发起请求（SSRF）。</p>
 *
 * <p>本策略提供两道默认关闭的门：</p>
 * <ol>
 *   <li><b>环境变量白名单</b>：默认空 = 拒绝一切 {@code api_key_env}；
 *       动态连接器不得引用环境变量，凭据应由静态配置提供。</li>
 *   <li><b>目标地址校验</b>：仅允许 http(s)；配置了 host 白名单时须命中白名单
 *       （命中即视为运维显式信任，不再做 DNS 解析）；未配置白名单时解析 host
 *       并逐个地址拦截回环 / 私网 / 链路本地（含 169.254.169.254）/ 组播 / CGNAT。</li>
 * </ol>
 *
 * <p>局限：注册期校验无法消除 DNS rebinding（校验后域名可被重新指向内网）。
 * 纵深防御上建议同时配置 host 白名单与出网防火墙。</p>
 */
@Component
public class PspTargetPolicy {

    private static final Logger log = LoggerFactory.getLogger(PspTargetPolicy.class);

    /** 允许被引用的环境变量名；空集合 = 全部拒绝。 */
    private final Set<String> apiKeyEnvAllowlist;

    /** 允许的外联 host；空集合 = 不限制 host，但仍做地址段校验。 */
    private final Set<String> hostAllowlist;

    public PspTargetPolicy(
            @Value("${nexus.gateway.psp.api-key-env-allowlist:}") String apiKeyEnvAllowlist,
            @Value("${nexus.gateway.psp.host-allowlist:}") String hostAllowlist) {
        // 环境变量名大小写敏感（Linux 下 PSP_KEY 与 psp_key 是两个变量），
        // host 大小写不敏感（DNS 规范）。
        this.apiKeyEnvAllowlist = split(apiKeyEnvAllowlist, false);
        this.hostAllowlist = split(hostAllowlist, true);
    }

    /**
     * base_url 是否允许作为动态 PSP 的外联目标。
     *
     * @param baseUrl 请求体中的 base_url
     * @return true 表示允许注册
     */
    public boolean isAllowedBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }

        // 白名单内的 host 由运维显式授信，直接放行（无需 DNS 解析）
        if (!hostAllowlist.isEmpty()) {
            return hostMatchesAllowlist(host);
        }

        // 未配置白名单：解析后逐个地址校验，确保不是内网 / 回环 / 元数据地址
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            log.warn("PSP base_url host [{}] 无法解析，拒绝注册: {}", host, e.getMessage());
            return false;
        }
        if (addresses.length == 0) {
            return false;
        }
        for (InetAddress addr : addresses) {
            if (!isPublicAddress(addr)) {
                log.warn("PSP base_url host [{}] 解析到非公网地址 {}，拒绝注册", host, addr);
                return false;
            }
        }
        return true;
    }

    /**
     * api_key_env 是否允许被引用。
     *
     * <p>白名单为空时一律拒绝（默认关闭）。匹配为大小写敏感全等，
     * 防止通过大小写变体绕过。</p>
     */
    public boolean isAllowedApiKeyEnv(String apiKeyEnv) {
        if (apiKeyEnvAllowlist.isEmpty()) {
            return false;
        }
        return apiKeyEnvAllowlist.contains(apiKeyEnv);
    }

    /** host 是否命中白名单：精确匹配，或以 “.domain” 作为后缀匹配。 */
    private boolean hostMatchesAllowlist(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        for (String entry : hostAllowlist) {
            if (normalized.equals(entry)
                    || (entry.startsWith(".") && normalized.endsWith(entry))
                    || normalized.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    /** 是否为可安全外联的公网地址（排除回环 / 私网 / 链路本地 / 组播 / CGNAT）。 */
    private static boolean isPublicAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return false;
        }
        // 运营商级 NAT（RFC 6598，100.64.0.0/10）：JDK 的 isSiteLocalAddress
        // 不覆盖该段，而云厂商常用其承载内部服务。
        if (addr instanceof java.net.Inet4Address) {
            byte[] b = addr.getAddress();
            int o1 = b[0] & 0xff;
            int o2 = b[1] & 0xff;
            if (o1 == 100 && (o2 & 0xc0) == 64) {
                return false;
            }
        }
        return true;
    }

    /**
     * 逗号分隔配置 → 去空白后的集合。
     *
     * @param lower true 表示转小写（host）；false 表示保留原样（环境变量名）
     */
    private static Set<String> split(String csv, boolean lower) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                out.add(lower ? v.toLowerCase(Locale.ROOT) : v);
            }
        }
        return out;
    }
}

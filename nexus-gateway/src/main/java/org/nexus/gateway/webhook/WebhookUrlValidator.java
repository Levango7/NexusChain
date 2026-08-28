package org.nexus.gateway.webhook;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Webhook 出站 URL 校验器（SSRF 防护，P1 审计项 2026-08-29）。
 *
 * <p>商户可配置 notify_url，投递服务会向该 URL 发起 POST。若不做校验，
 * 攻击者可指向内网地址（如 Nacos / Redis / PostgreSQL / 云元数据服务
 * 169.254.169.254）进行探测或利用。本组件在投递前执行：
 * <ol>
 *   <li>scheme 仅允许 http/https</li>
 *   <li>IP 字面量：拒绝环回、私网、链路本地、CGNAT、基准测试段、组播/保留段</li>
 *   <li>域名：拒绝 localhost / .local / .internal / .lan / .home 等内网保留域名，
 *       并对 DNS 解析出的<b>全部</b>地址执行 IP 级校验（缓解 DNS rebinding）</li>
 *   <li>IPv4-mapped IPv6（::ffff:a.b.c.d）解包后按 IPv4 规则校验</li>
 * </ol>
 *
 * <p>安全模型说明：解析时校验可拦截大多数重绑定攻击，但不能 100% 防御
 * TOCTOU 型 DNS rebinding（解析后到连接前地址变化）。如部署环境可承受
 * 额外成本，建议叠加出口代理 + 防火墙对出站流量做网络层限制。
 */
@Component
public class WebhookUrlValidator {

    /**
     * 校验 Webhook 回调 URL，非法时抛出 {@link IllegalArgumentException}。
     *
     * @param url 商户提供的回调地址
     * @throws IllegalArgumentException URL 非法 / 指向内网保留地址
     */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Webhook URL 为空");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Webhook URL 非法: " + url, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Webhook URL 仅支持 http/https: " + url);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL 缺少 host: " + url);
        }

        // 1) IP 字面量：直接按 IP 校验，不依赖 DNS
        if (looksLikeIpLiteral(host)) {
            InetAddress literal = resolveNoDns(host);
            if (literal == null) {
                throw new IllegalArgumentException("Webhook URL IP 无法解析: " + url);
            }
            rejectIfBlocked(literal, url);
            return;
        }

        // 2) 域名：拒绝内网保留域名（含 localhost 变体）
        String lower = host.toLowerCase();
        if (lower.equals("localhost")
                || lower.endsWith(".localhost")
                || lower.endsWith(".local")
                || lower.endsWith(".internal")
                || lower.endsWith(".lan")
                || lower.endsWith(".home")
                || lower.endsWith(".home.arpa")
                || lower.endsWith(".corp")) {
            throw new IllegalArgumentException("Webhook URL host 为内网/保留域名: " + url);
        }

        // 3) 域名：解析全部地址并逐 IP 校验
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // fail-closed：解析失败视为不可信
            throw new IllegalArgumentException("Webhook URL DNS 解析失败(已按不安全处理): " + url, e);
        }
        for (InetAddress addr : resolved) {
            rejectIfBlocked(addr, url);
        }
    }

    /** IPv4 字面量（a.b.c.d）或含冒号的 IPv6 字面量。 */
    private static boolean looksLikeIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** 对 IP 字面量做解析（不触发 DNS 搜索）。 */
    private static InetAddress resolveNoDns(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static void rejectIfBlocked(InetAddress addr, String url) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            throw new IllegalArgumentException(
                    "Webhook URL 指向内网/保留地址，已拒绝(SSRF防护): " + url);
        }

        byte[] raw = addr.getAddress();
        if (raw == null) {
            throw new IllegalArgumentException("Webhook URL 地址无效: " + url);
        }

        // IPv4-mapped IPv6（::ffff:a.b.c.d）解包后按 IPv4 规则再校验
        if (raw.length == 16 && isIpv4Mapped(raw)) {
            try {
                rejectIfBlocked(InetAddress.getByAddress(Arrays.copyOfRange(raw, 12, 16)), url);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Webhook URL 地址无效: " + url, e);
            }
            return;
        }

        if (raw.length == 16) {
            // fc00::/7 ULA（唯一本地地址）——Java isSiteLocalAddress 只认已废弃的 fec0::/10，
            // 需显式拦截 fc00::/7（含 fd00::/8 前缀）
            int b0 = raw[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) {
                throw new IllegalArgumentException(
                        "Webhook URL 指向 ULA 保留段(fc00::/7)，已拒绝(SSRF防护): " + url);
            }
            // 2001:db8::/32 文档保留段
            if (b0 == 0x20 && (raw[1] & 0xFF) == 0x01 && (raw[2] & 0xFF) == 0x0D && (raw[3] & 0xFF) == 0xB8) {
                throw new IllegalArgumentException(
                        "Webhook URL 指向文档保留段(2001:db8::/32)，已拒绝(SSRF防护): " + url);
            }
        }

        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            // 100.64.0.0/10 CGNAT
            if (b0 == 100 && (b1 & 0xC0) == 64) {
                throw new IllegalArgumentException(
                        "Webhook URL 指向 CGNAT 保留段(100.64.0.0/10)，已拒绝(SSRF防护): " + url);
            }
            // 198.18.0.0/15 benchmark（第二字节 18-19，掩码后等于 18）
            if (b0 == 198 && (b1 & 0xFE) == 18) {
                throw new IllegalArgumentException(
                        "Webhook URL 指向基准测试保留段(198.18.0.0/15)，已拒绝(SSRF防护): " + url);
            }
            // 240.0.0.0/4 保留（isMulticastAddress 仅覆盖 224/4）
            if (b0 >= 240) {
                throw new IllegalArgumentException(
                        "Webhook URL 指向保留地址段(240.0.0.0/4)，已拒绝(SSRF防护): " + url);
            }
        }
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        if (raw.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
    }
}

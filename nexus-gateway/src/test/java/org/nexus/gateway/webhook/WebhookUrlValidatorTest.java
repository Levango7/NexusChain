package org.nexus.gateway.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WebhookUrlValidator 单元测试（SSRF 防护，P1 审计项 2026-08-29）。
 *
 * <p>测试原则：合法/非法用例均使用 IP 字面量或本机可判定的域名，
 * 不依赖外部 DNS 网络（避免 CI/本地断网导致 flaky）。
 */
class WebhookUrlValidatorTest {

    private final WebhookUrlValidator validator = new WebhookUrlValidator();

    // ---------- 合法 ----------

    @Test
    @DisplayName("公网 IP 字面量允许")
    void publicIpLiteralAllowed() {
        assertDoesNotThrow(() -> validator.validate("http://8.8.8.8/webhook"));
        assertDoesNotThrow(() -> validator.validate("https://1.1.1.1/hook"));
        assertDoesNotThrow(() -> validator.validate("https://8.8.4.4:8443/cb"));
    }

    @Test
    @DisplayName("带端口/路径/query 的公网地址允许")
    void publicUrlWithPortAndQueryAllowed() {
        assertDoesNotThrow(() -> validator.validate("https://8.8.8.8:443/cb?event=paid"));
    }

    // ---------- scheme 非法 ----------

    @Test
    @DisplayName("非 http/https scheme 拒绝")
    void nonHttpSchemeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("ftp://8.8.8.8/file"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("gopher://8.8.8.8:6379/x"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("javascript:alert(1)"));
    }

    // ---------- 环回 / localhost ----------

    @Test
    @DisplayName("localhost 及变体拒绝")
    void localhostRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://localhost:8080/webhook"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://127.0.0.1:6379/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://0.0.0.0:8080/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://myhost.localhost/cb"));
    }

    // ---------- 私网段 ----------

    @Test
    @DisplayName("私网 IP 字面量拒绝（10/8 172.16/12 192.168/16）")
    void privateIpRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://10.0.0.1:3306/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://172.16.0.1:8080/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://192.168.1.1:7001/"));
    }

    @Test
    @DisplayName("链路本地 / 元数据地址拒绝（169.254/16）")
    void linkLocalRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    @DisplayName("CGNAT 100.64.0.0/10 拒绝")
    void cgnatRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://100.64.0.1/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://100.127.255.254/"));
    }

    @Test
    @DisplayName("基准测试段 198.18.0.0/15 与保留段 240/4 拒绝")
    void benchmarkAndReservedRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://198.18.0.1/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://240.0.0.1/"));
    }

    // ---------- IPv6 ----------

    @Test
    @DisplayName("IPv6 环回/链路本地/ULA 拒绝，公网 IPv6 允许")
    void ipv6Handling() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://[::1]:8080/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://[fe80::1]/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://[fc00::1]/"));
        // IPv4-mapped IPv6 解包后按私网拒绝
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://[::ffff:192.168.1.1]:8080/"));
        assertDoesNotThrow(() -> validator.validate("http://[2606:4700:4700::1111]/"));
    }

    // ---------- 内网保留域名 ----------

    @Test
    @DisplayName("内网保留域名拒绝（不触发 DNS）")
    void reservedDomainRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://redis.internal:6379/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://nacos.local:8848/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://db.lan/"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http://pg.home:5432/"));
    }

    // ---------- 缺 host / 畸形 URL ----------

    @Test
    @DisplayName("缺 host 或畸形 URL 拒绝")
    void malformedUrlRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("http:///no-host"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("not a url"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(""));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(null));
    }
}

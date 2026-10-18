package org.nexus.signing.config;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtTokenProvider} 单元测试（与 gateway 侧同型，批次⑤ P0 加固，2026-10-18）。
 *
 * <p>覆盖：签发→解析往返（subject/roles claim）、require-secret 启动守卫
 * （缺失/弱密钥启动期 fail-fast）、非守卫档位空密钥随机兜底可用。</p>
 */
class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        // 测试密钥：≥32 字节（HS256 要求），与生产 JWT_SECRET 注入方式等价
        ReflectionTestUtils.setField(provider, "secret",
                "test-secret-key-0123456789-0123456789-0123456789");
        provider.init();
    }

    @Test
    void generateAndParse_shouldRoundTrip() {
        String token = provider.generateToken("gateway", List.of("SIGNING_SERVICE"));

        Claims claims = provider.parseClaims(token);
        assertEquals("gateway", claims.getSubject());
        assertEquals(List.of("SIGNING_SERVICE"), JwtTokenProvider.extractRoles(claims));
        assertTrue(provider.validateToken(token));
    }

    // --- require-secret 启动守卫（批次⑤ P0 加固，2026-10-18） ---

    @Test
    void requireSecret_true_withoutSecret_failsFastOnInit() {
        JwtTokenProvider strict = new JwtTokenProvider();
        ReflectionTestUtils.setField(strict, "secret", "");
        ReflectionTestUtils.setField(strict, "requireSecret", true);

        IllegalStateException e = assertThrows(IllegalStateException.class, strict::init);
        assertTrue(e.getMessage().contains("require-secret"));
    }

    @Test
    void requireSecret_true_withShortSecret_failsFastOnInit() {
        JwtTokenProvider strict = new JwtTokenProvider();
        ReflectionTestUtils.setField(strict, "secret", "short-secret");
        ReflectionTestUtils.setField(strict, "requireSecret", true);

        IllegalStateException e = assertThrows(IllegalStateException.class, strict::init);
        assertTrue(e.getMessage().contains("32"));
    }

    @Test
    void requireSecret_false_withoutSecret_startsWithRandomKey() {
        // 默认档位（dev / 单测）保持既有行为：告警 + 进程内随机密钥，自签自验可用
        JwtTokenProvider lenientProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(lenientProvider, "secret", "");

        assertDoesNotThrow(lenientProvider::init);

        String token = lenientProvider.generateToken("svc", List.of("ADMIN"));
        assertTrue(lenientProvider.validateToken(token));
    }
}

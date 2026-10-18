package org.nexus.gateway.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtTokenProvider} 单元测试。
 *
 * <p>覆盖：签发→解析往返（subject/roles claim）、过期拒绝、篡改拒绝、
 * 非法格式拒绝、角色提取（含空白项清理）、require-secret 启动守卫（批次⑤ P0 加固）。</p>
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
        String token = provider.generateToken("admin-op", List.of("ADMIN", "OPERATOR"));

        Claims claims = provider.parseClaims(token);

        assertEquals("admin-op", claims.getSubject());
        assertEquals(List.of("ADMIN", "OPERATOR"), JwtTokenProvider.extractRoles(claims));
        assertTrue(provider.validateToken(token));
    }

    @Test
    void expiredToken_shouldBeRejected() throws InterruptedException {
        // TTL 极短：签发后立即过期
        String token = provider.generateToken("admin-op", List.of("ADMIN"), 1L);
        Thread.sleep(50L);

        assertFalse(provider.validateToken(token));
        assertThrows(io.jsonwebtoken.ExpiredJwtException.class,
                () -> provider.parseClaims(token));
    }

    @Test
    void tamperedToken_shouldBeRejected() {
        String token = provider.generateToken("admin-op", List.of("ADMIN"));

        // 篡改 payload（伪造角色）
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + "tamperedPayload" + "." + parts[2];

        assertFalse(provider.validateToken(forged));
    }

    @Test
    void malformedToken_shouldBeRejected() {
        assertFalse(provider.validateToken("not-a-jwt"));
        // jjwt 对非 JWT 结构抛 JwtException 子类（MalformedJwtException 等）
        assertThrows(io.jsonwebtoken.JwtException.class,
                () -> provider.parseClaims("not-a-jwt"));
    }

    @Test
    void extractRoles_shouldHandleBlankAndNulls() {
        Claims claims = provider.parseClaims(
                provider.generateToken("s", List.of(" A ", "", "B")));

        assertEquals(List.of("A", "B"), JwtTokenProvider.extractRoles(claims));
    }

    @Test
    void extractRoles_emptyRolesClaim_shouldReturnEmpty() {
        // 空 roles 列表 → roles claim 为空串 → 提取为空列表
        String token = provider.generateToken("s", List.of());
        Claims claims = provider.parseClaims(token);

        assertTrue(JwtTokenProvider.extractRoles(claims).isEmpty());
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
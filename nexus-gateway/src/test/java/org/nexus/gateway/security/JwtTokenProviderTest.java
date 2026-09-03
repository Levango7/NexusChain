package org.nexus.gateway.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtTokenProvider} 单元测试。
 *
 * <p>覆盖：签发→解析往返（subject/roles claim）、过期拒绝、篡改拒绝、
 * 非法格式拒绝、角色提取（含空白项清理）。</p>
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
}
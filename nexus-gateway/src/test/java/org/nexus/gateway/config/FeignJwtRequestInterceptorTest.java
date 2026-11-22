package org.nexus.gateway.config;

import feign.RequestTemplate;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeignJwtRequestInterceptorTest {

    private static final String DEFAULT_ROLE_PLACEHOLDER = "${JWT_SERVICE_ROLES:SIGNING_SERVICE,READ}";

    @Test
    void applicationDefaultRetainsEnvironmentOverrideAndReadRole() throws IOException {
        assertEquals(DEFAULT_ROLE_PLACEHOLDER, jwtConfig().get("service-roles"));
    }

    @Test
    void issuedTokenUsesLegacySignerAndReadFromApplicationDefault() throws IOException {
        String placeholder = String.valueOf(jwtConfig().get("service-roles"));
        String roles = placeholder.substring("${JWT_SERVICE_ROLES:".length(), placeholder.length() - 1);
        byte[] random = new byte[48];
        new SecureRandom().nextBytes(random);
        String secret = Base64.getEncoder().encodeToString(random);

        FeignJwtRequestInterceptor interceptor = new FeignJwtRequestInterceptor();
        ReflectionTestUtils.setField(interceptor, "secret", secret);
        ReflectionTestUtils.setField(interceptor, "serviceSubject", "nexus-gateway");
        ReflectionTestUtils.setField(interceptor, "serviceRoles", roles);
        ReflectionTestUtils.setField(interceptor, "ttlMillis", 300_000L);
        interceptor.init();
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> authorization = template.headers().get(FeignJwtRequestInterceptor.AUTHORIZATION_HEADER);
        assertEquals(1, authorization.size());
        String token = authorization.iterator().next().substring(FeignJwtRequestInterceptor.BEARER_PREFIX.length());
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertEquals("nexus-gateway", claims.getSubject());
        assertTrue(Arrays.asList(String.valueOf(claims.get("roles")).split(",")).contains("SIGNING_SERVICE"));
        assertTrue(Arrays.asList(String.valueOf(claims.get("roles")).split(",")).contains("READ"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jwtConfig() throws IOException {
        try (InputStream input = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(input);
            Map<String, Object> nexus = (Map<String, Object>) root.get("nexus");
            Map<String, Object> security = (Map<String, Object>) nexus.get("security");
            return (Map<String, Object>) security.get("jwt");
        }
    }
}

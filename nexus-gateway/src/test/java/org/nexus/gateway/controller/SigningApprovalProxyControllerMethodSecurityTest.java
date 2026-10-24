package org.nexus.gateway.controller;

import feign.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.nexus.gateway.client.feign.SigningApprovalFeignClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code @PreAuthorize("hasRole('ADMIN')")} 的真实代理测试。
 *
 * <p>直接调控制器：方法安全只有在 Spring 代理上才生效，所以这里用最小上下文
 * 启用 {@code @EnableMethodSecurity}，验证匿名 / 商户 / 管理员三种身份的
 * 放行结果——这正是"运维入口只给 ADMIN"的判据。</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SigningApprovalProxyControllerMethodSecurityTest.TestConfig.class)
class SigningApprovalProxyControllerMethodSecurityTest {

    @Autowired
    private SigningApprovalProxyController controller;

    @Autowired
    private SigningApprovalFeignClient client;

    @BeforeEach
    void resetMock() {
        Mockito.reset(client);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("匿名调用被方法安全拒绝，且不触达下游")
    void anonymousIsDenied() {
        // 网关 SecurityFilterChain 保留默认 AnonymousAuthenticationFilter，
        // 真实匿名请求的身份就是它（而不是空 SecurityContext）。
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("anonymous-key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThrows(AccessDeniedException.class,
                () -> controller.decide("req-1", "Bearer x", Map.of("decision", "APPROVE")));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("商户角色（MERCHANT）不能下审批决策")
    void merchantIsDenied() {
        authenticate("merchant-1", "ROLE_MERCHANT");

        assertThrows(AccessDeniedException.class,
                () -> controller.decide("req-2", "Bearer x", Map.of("decision", "APPROVE")));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("ADMIN 可以调用，并转发到下游")
    void adminIsAllowed() {
        authenticate("alice@nexus", "ROLE_ADMIN");
        when(client.decide(anyString(), anyString(), anyMap()))
                .thenReturn(Response.builder()
                        .request(feign.Request.create(feign.Request.HttpMethod.POST,
                                "http://signing-service/api/v1/signing/approvals/stub/decision",
                                Map.of(), new byte[0], StandardCharsets.UTF_8))
                        .status(200).reason("OK").headers(Map.of())
                        .body("{\"statusCode\":2000}", StandardCharsets.UTF_8).build());

        assertEquals(200,
                controller.decide("req-3", "Bearer x", Map.of("decision", "APPROVE")).getStatusCode().value());
    }

    private static void authenticate(String subject, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, null,
                        List.of(new SimpleGrantedAuthority(authority))));
    }

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        SigningApprovalFeignClient signingApprovalFeignClient() {
            return Mockito.mock(SigningApprovalFeignClient.class);
        }

        @Bean
        SigningApprovalProxyController signingApprovalProxyController(
                SigningApprovalFeignClient signingApprovalFeignClient) {
            return new SigningApprovalProxyController(signingApprovalFeignClient);
        }
    }
}

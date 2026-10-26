package org.nexus.gateway.authz;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.nexus.gateway.reconciliation.link.ReconciliationAdjustmentService;
import org.nexus.gateway.reconciliation.link.ReconciliationLinkController;
import org.nexus.gateway.reconciliation.link.SuspenseWriteoffService;
import org.nexus.gateway.reconciliation.link.TransactionAuditService;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 对账联动控制器的跨租户鉴权测试（2026-10-27）。
 *
 * <p>{@code /api/reconciliation/link} 有意保留在 {@code ApiKeyInterceptor} 之外
 * （JWT 鉴权），因此没有 {@code nexus.merchantId} 上下文，读端点无法像
 * {@code AuditEventController} 那样做 {@code MerchantOwnershipGuard} 归属比对。
 * 原先类级放行 MERCHANT，等于允许任意商户 JWT 读取他人调整/审计数据；
 * 现收紧为 ADMIN-only。</p>
 *
 * <p>方法安全只在 Spring 代理上生效，故用最小上下文启用
 * {@code @EnableMethodSecurity}。"被拒且不触达下游"是成对断言——
 * 只断言异常会漏掉"鉴权发生在 Service 之后 ⇒ 数据已读出来"的形态。</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ReconciliationLinkAuthzTest.TestConfig.class)
class ReconciliationLinkAuthzTest {

    @Autowired
    private ReconciliationLinkController controller;

    @Autowired
    private ReconciliationAdjustmentService adjustmentService;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(adjustmentService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("MERCHANT 读跨商户调整记录被拒，且不触达 Service")
    void merchantDenied() {
        authenticate("merchant-1", "ROLE_MERCHANT");

        assertThrows(AccessDeniedException.class,
                () -> controller.getAdjustmentsByMerchant(999L));
        verifyNoInteractions(adjustmentService);
    }

    @Test
    @DisplayName("匿名被拒")
    void anonymousDenied() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("anonymous-key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThrows(AccessDeniedException.class,
                () -> controller.getAdjustmentsByMerchant(999L));
        verifyNoInteractions(adjustmentService);
    }

    @Test
    @DisplayName("ADMIN 放行")
    void adminAllowed() {
        authenticate("alice@nexus", "ROLE_ADMIN");
        when(adjustmentService.getAdjustmentsByMerchant(999L)).thenReturn(List.of());

        assertDoesNotThrow(() -> controller.getAdjustmentsByMerchant(999L));
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
        ReconciliationAdjustmentService reconciliationAdjustmentService() {
            return Mockito.mock(ReconciliationAdjustmentService.class);
        }

        @Bean
        SuspenseWriteoffService suspenseWriteoffService() {
            return Mockito.mock(SuspenseWriteoffService.class);
        }

        @Bean
        TransactionAuditService transactionAuditService() {
            return Mockito.mock(TransactionAuditService.class);
        }

        @Bean
        ReconciliationLinkController reconciliationLinkController(
                ReconciliationAdjustmentService adjustmentService,
                SuspenseWriteoffService suspenseWriteoffService,
                TransactionAuditService transactionAuditService) {
            return new ReconciliationLinkController(
                    adjustmentService, suspenseWriteoffService, transactionAuditService);
        }
    }
}

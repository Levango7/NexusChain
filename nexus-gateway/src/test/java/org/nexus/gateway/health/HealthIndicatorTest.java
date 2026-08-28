package org.nexus.gateway.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 健康指标单元测试：ChainNode / SigningService / WalletService。
 */
class HealthIndicatorTest {

    // === ChainNodeHealthIndicator ===

    @Test
    @DisplayName("ChainNodeHealthIndicator: RPC 可达返回 UP")
    void chainNode_up() {
        GatewayConfig cfg = new GatewayConfig();
        cfg.getChain().setRpcUrl("http://localhost:19585");

        ChainNodeHealthIndicator indicator = new ChainNodeHealthIndicator(cfg);
        // 注入 mock RestTemplate（原构造内 new RestTemplate()）
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(String.class)))
                .thenReturn(new org.springframework.http.ResponseEntity<>("ok", org.springframework.http.HttpStatus.OK));
        setField(indicator, "restTemplate", rt);

        Health h = indicator.health();
        assertEquals(Status.UP, h.getStatus());
    }

    @Test
    @DisplayName("ChainNodeHealthIndicator: RPC 不可达返回 DOWN")
    void chainNode_down() {
        GatewayConfig cfg = new GatewayConfig();
        cfg.getChain().setRpcUrl("http://invalid:19585");

        ChainNodeHealthIndicator indicator = new ChainNodeHealthIndicator(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("conn refused"));
        setField(indicator, "restTemplate", rt);

        Health h = indicator.health();
        assertEquals(Status.DOWN, h.getStatus());
        assertNotNull(h.getDetails().get("error"));
    }

    // === SigningServiceHealthIndicator ===

    @Test
    @DisplayName("SigningServiceHealthIndicator: signTransfer 不抛异常返回 UP")
    void signingService_up() {
        SigningServiceFeignClient client = mock(SigningServiceFeignClient.class);
        when(client.signTransfer(any(), any(), any())).thenReturn("ok");

        SigningServiceHealthIndicator indicator = new SigningServiceHealthIndicator(client);
        Health h = indicator.health();
        assertEquals(Status.UP, h.getStatus());
        assertEquals("nexus-signing-service", h.getDetails().get("service"));
    }

    @Test
    @DisplayName("SigningServiceHealthIndicator: 抛异常返回 DOWN")
    void signingService_down() {
        SigningServiceFeignClient client = mock(SigningServiceFeignClient.class);
        when(client.signTransfer(any(), any(), any())).thenThrow(new RuntimeException("unreachable"));

        SigningServiceHealthIndicator indicator = new SigningServiceHealthIndicator(client);
        Health h = indicator.health();
        assertEquals(Status.DOWN, h.getStatus());
        assertNotNull(h.getDetails().get("error"));
    }

    // === WalletServiceHealthIndicator ===

    @Test
    @DisplayName("WalletServiceHealthIndicator: isAddressWhitelisted 不抛异常返回 UP")
    void walletService_up() {
        WalletMgmtFeignClient client = mock(WalletMgmtFeignClient.class);
        when(client.isAddressWhitelisted(anyString())).thenReturn(true);

        WalletServiceHealthIndicator indicator = new WalletServiceHealthIndicator(client);
        Health h = indicator.health();
        assertEquals(Status.UP, h.getStatus());
        assertEquals("nexus-wallet-service", h.getDetails().get("service"));
    }

    @Test
    @DisplayName("WalletServiceHealthIndicator: 抛异常返回 DOWN")
    void walletService_down() {
        WalletMgmtFeignClient client = mock(WalletMgmtFeignClient.class);
        when(client.isAddressWhitelisted(anyString())).thenThrow(new RuntimeException("unreachable"));

        WalletServiceHealthIndicator indicator = new WalletServiceHealthIndicator(client);
        Health h = indicator.health();
        assertEquals(Status.DOWN, h.getStatus());
        assertNotNull(h.getDetails().get("error"));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
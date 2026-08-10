package org.nexus.gateway.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GatewayConfig;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 链节点 RPC 客户端单元测试：ChainRpcClient + ConsortiumRpcClient。
 *
 * <p>两个客户端在构造函数内 new RestTemplate()，通过反射注入 mock RestTemplate
 * 以验证 HTTP 调用契约与降级分支。</p>
 */
class RpcClientTest {

    private GatewayConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new GatewayConfig();
        cfg.getChain().setRpcUrl("http://chain.test");
        cfg.getConsortium().setRpcUrl("http://consortium.test");
    }

    // === ChainRpcClient ===

    @Test
    @DisplayName("ChainRpcClient.isTransactionConfirmed: data=2000 返回 true")
    void chainConfirmed_true() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("data", 2000), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertTrue(client.isTransactionConfirmed("0xabc"));
    }

    @Test
    @DisplayName("ChainRpcClient.isTransactionConfirmed: data=2100 返回 false")
    void chainConfirmed_false() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("data", 2100), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertFalse(client.isTransactionConfirmed("0xabc"));
    }

    @Test
    @DisplayName("ChainRpcClient.isTransactionConfirmed: 异常 + skipConfirmation + 长哈希 返回 true")
    void chainConfirmed_skipConfirmationFallback() {
        cfg.getChain().setSkipConfirmation(true);
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertTrue(client.isTransactionConfirmed("0xabcdef0123456789")); // length >= 16
    }

    @Test
    @DisplayName("ChainRpcClient.isTransactionConfirmed: 异常 + skipConfirmation + 短哈希 返回 false")
    void chainConfirmed_skipConfirmationShortHash() {
        cfg.getChain().setSkipConfirmation(true);
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertFalse(client.isTransactionConfirmed("0xshort"));
    }

    @Test
    @DisplayName("ChainRpcClient.isTransactionConfirmed: 异常 + 不 skip 返回 false")
    void chainConfirmed_exceptionNoSkip() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertFalse(client.isTransactionConfirmed("0xabc"));
    }

    @Test
    @DisplayName("ChainRpcClient.isTransactionConfirmed: null body 返回 false")
    void chainConfirmed_nullBody() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertFalse(client.isTransactionConfirmed("0xabc"));
    }

    @Test
    @DisplayName("ChainRpcClient.getBlockHeight: 返回 data 数值")
    void chainBlockHeight() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("data", 12345L), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertEquals(12345L, client.getBlockHeight());
    }

    @Test
    @DisplayName("ChainRpcClient.getBlockHeight: 异常返回 -1")
    void chainBlockHeight_exception() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertEquals(-1L, client.getBlockHeight());
    }

    @Test
    @DisplayName("ChainRpcClient.getNonce: data 字段返回 nonce")
    void chainNonce_dataField() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("data", 7L), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertEquals(7L, client.getNonce("0xhash"));
    }

    @Test
    @DisplayName("ChainRpcClient.getNonce: nonce 字段返回 nonce（兼容形状）")
    void chainNonce_nonceField() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("nonce", 9L), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertEquals(9L, client.getNonce("0xhash"));
    }

    @Test
    @DisplayName("ChainRpcClient.getNonce: 异常返回 -1")
    void chainNonce_exception() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertEquals(-1L, client.getNonce("0xhash"));
    }

    @Test
    @DisplayName("ChainRpcClient.broadcastTransaction: code=2000 返回 true")
    void chainBroadcast_success() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("code", 2000), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertTrue(client.broadcastTransaction("signed-hex"));
    }

    @Test
    @DisplayName("ChainRpcClient.broadcastTransaction: code!=2000 返回 false")
    void chainBroadcast_failure() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("code", 4000), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertFalse(client.broadcastTransaction("signed-hex"));
    }

    @Test
    @DisplayName("ChainRpcClient.broadcastTransaction: 异常返回 false")
    void chainBroadcast_exception() {
        ChainRpcClient client = new ChainRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertFalse(client.broadcastTransaction("signed-hex"));
    }

    // === ConsortiumRpcClient（与 ChainRpcClient 同构，重点验证 consortium 配置路由）===

    @Test
    @DisplayName("ConsortiumRpcClient.isTransactionConfirmed: data=2000 返回 true")
    void consortiumConfirmed_true() {
        ConsortiumRpcClient client = new ConsortiumRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("data", 2000), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertTrue(client.isTransactionConfirmed("0xabc"));
    }

    @Test
    @DisplayName("ConsortiumRpcClient.isTransactionConfirmed: 异常 + skipConfirmation + 长哈希 返回 true")
    void consortiumConfirmed_skipFallback() {
        cfg.getConsortium().setSkipConfirmation(true);
        ConsortiumRpcClient client = new ConsortiumRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertTrue(client.isTransactionConfirmed("0xabcdef0123456789"));
    }

    @Test
    @DisplayName("ConsortiumRpcClient.getBlockHeight: 返回 data 数值")
    void consortiumBlockHeight() {
        ConsortiumRpcClient client = new ConsortiumRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("data", 999L), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertEquals(999L, client.getBlockHeight());
    }

    @Test
    @DisplayName("ConsortiumRpcClient.getBlockHeight: 异常返回 -1")
    void consortiumBlockHeight_exception() {
        ConsortiumRpcClient client = new ConsortiumRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertEquals(-1L, client.getBlockHeight());
    }

    @Test
    @DisplayName("ConsortiumRpcClient.getNonce: data 字段返回 nonce")
    void consortiumNonce() {
        ConsortiumRpcClient client = new ConsortiumRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("data", 3L), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertEquals(3L, client.getNonce("0xhash"));
    }

    @Test
    @DisplayName("ConsortiumRpcClient.broadcastTransaction: code=2000 返回 true")
    void consortiumBroadcast() {
        ConsortiumRpcClient client = new ConsortiumRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("code", 2000), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertTrue(client.broadcastTransaction("signed-hex"));
    }

    @Test
    @DisplayName("ConsortiumRpcClient.broadcastTransaction: 异常返回 false")
    void consortiumBroadcast_exception() {
        ConsortiumRpcClient client = new ConsortiumRpcClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertFalse(client.broadcastTransaction("signed-hex"));
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
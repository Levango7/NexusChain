package org.nexus.gateway.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.sdk.client.SigningServiceClient;
import org.nexus.sdk.client.WalletMgmtClient;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HTTP 客户端单元测试：HttpSigningServiceClient + HttpWalletMgmtClient + ExchangeWalletClient。
 */
class HttpClientTest {

    private GatewayConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new GatewayConfig();
        cfg.getExchangeWallet().setBaseUrl("http://wallet.test");
        cfg.getExchangeWallet().setSignPath("/api/v1/transfers/sign");
    }

    // === HttpSigningServiceClient ===

    @Test
    @DisplayName("HttpSigningServiceClient.signTransfer: statusCode=2000 返回 data")
    void signing_signTransfer_success() {
        HttpSigningServiceClient client = new HttpSigningServiceClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("statusCode", 2000, "data", "0xTxHash"), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertEquals("0xTxHash", client.signTransfer("from", "to", BigDecimal.ONE));
    }

    @Test
    @DisplayName("HttpSigningServiceClient.signTransfer: statusCode!=2000 返回 null")
    void signing_signTransfer_failure() {
        HttpSigningServiceClient client = new HttpSigningServiceClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("statusCode", 4000), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertNull(client.signTransfer("from", "to", BigDecimal.ONE));
    }

    @Test
    @DisplayName("HttpSigningServiceClient.signTransfer: 异常返回 null")
    void signing_signTransfer_exception() {
        HttpSigningServiceClient client = new HttpSigningServiceClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertNull(client.signTransfer("from", "to", BigDecimal.ONE));
    }

    @Test
    @DisplayName("HttpSigningServiceClient.signTransfer: null body 返回 null")
    void signing_signTransfer_nullBody() {
        HttpSigningServiceClient client = new HttpSigningServiceClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertNull(client.signTransfer("from", "to", BigDecimal.ONE));
    }

    @Test
    @DisplayName("HttpSigningServiceClient.transfer: statusCode=2000 返回 data")
    void signing_transfer_success() {
        HttpSigningServiceClient client = new HttpSigningServiceClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("statusCode", 2000, "data", "0xTxHash"), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertEquals("0xTxHash", client.transfer("from", "to", BigDecimal.ONE, "priv"));
    }

    @Test
    @DisplayName("HttpSigningServiceClient.transfer: 异常返回 null")
    void signing_transfer_exception() {
        HttpSigningServiceClient client = new HttpSigningServiceClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertNull(client.transfer("from", "to", BigDecimal.ONE, "priv"));
    }

    @Test
    @DisplayName("HttpSigningServiceClient.canSignViaMpc: PoC 阶段返回 false")
    void signing_canSignViaMpc() {
        HttpSigningServiceClient client = new HttpSigningServiceClient(cfg);
        assertFalse(client.canSignViaMpc(BigDecimal.ONE));
    }

    // === HttpWalletMgmtClient ===

    @Test
    @DisplayName("HttpWalletMgmtClient.addressToPubkeyHash: statusCode=2000 返回 data")
    void wallet_addressToPubkeyHash_success() {
        HttpWalletMgmtClient client = new HttpWalletMgmtClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("statusCode", 2000, "data", "pubkeyHash"), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertEquals("pubkeyHash", client.addressToPubkeyHash("NEX-ADDR"));
    }

    @Test
    @DisplayName("HttpWalletMgmtClient.addressToPubkeyHash: 异常返回 null")
    void wallet_addressToPubkeyHash_exception() {
        HttpWalletMgmtClient client = new HttpWalletMgmtClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertNull(client.addressToPubkeyHash("NEX-ADDR"));
    }

    @Test
    @DisplayName("HttpWalletMgmtClient.addressToPubkeyHash: statusCode!=2000 返回 null")
    void wallet_addressToPubkeyHash_failure() {
        HttpWalletMgmtClient client = new HttpWalletMgmtClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("statusCode", 4000), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertNull(client.addressToPubkeyHash("NEX-ADDR"));
    }

    @Test
    @DisplayName("HttpWalletMgmtClient.verifyAddress: statusCode=2000 返回 true")
    void wallet_verifyAddress_success() {
        HttpWalletMgmtClient client = new HttpWalletMgmtClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("statusCode", 2000), HttpStatus.OK));
        setField(client, "restTemplate", rt);

        assertTrue(client.verifyAddress("NEX-ADDR"));
    }

    @Test
    @DisplayName("HttpWalletMgmtClient.verifyAddress: 异常返回 false")
    void wallet_verifyAddress_exception() {
        HttpWalletMgmtClient client = new HttpWalletMgmtClient(cfg);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForEntity(anyString(), eq(Map.class))).thenThrow(new RuntimeException("down"));
        setField(client, "restTemplate", rt);

        assertFalse(client.verifyAddress("NEX-ADDR"));
    }

    @Test
    @DisplayName("HttpWalletMgmtClient.isAddressWhitelisted: PoC 阶段返回 false")
    void wallet_isAddressWhitelisted() {
        HttpWalletMgmtClient client = new HttpWalletMgmtClient(cfg);
        assertFalse(client.isAddressWhitelisted("NEX-ADDR"));
    }

    @Test
    @DisplayName("HttpWalletMgmtClient.getCustodyTier: PoC 阶段返回 HOT")
    void wallet_getCustodyTier() {
        HttpWalletMgmtClient client = new HttpWalletMgmtClient(cfg);
        assertEquals("HOT", client.getCustodyTier("wallet-1"));
    }

    // === ExchangeWalletClient（兼容委托层）===

    @Test
    @DisplayName("ExchangeWalletClient: 委托 signing/wallet 客户端")
    void exchangeWallet_delegates() {
        SigningServiceClient signing = mock(SigningServiceClient.class);
        WalletMgmtClient wallet = mock(WalletMgmtClient.class);
        when(signing.signTransfer("from", "to", BigDecimal.ONE)).thenReturn("0xTx");
        when(signing.transfer("from", "to", BigDecimal.ONE, "priv")).thenReturn("0xTx2");
        when(wallet.addressToPubkeyHash("addr")).thenReturn("hash");
        when(wallet.verifyAddress("addr")).thenReturn(true);

        ExchangeWalletClient client = new ExchangeWalletClient(wallet, signing);
        assertEquals("0xTx", client.signTransfer("from", "to", BigDecimal.ONE));
        assertEquals("0xTx2", client.transfer("from", "to", BigDecimal.ONE, "priv"));
        assertEquals("hash", client.addressToPubkeyHash("addr"));
        assertTrue(client.verifyAddress("addr"));
        assertSame(wallet, client.getWalletMgmtClient());
        assertSame(signing, client.getSigningServiceClient());
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
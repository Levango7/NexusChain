package org.nexus.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.model.Subscription;
import org.nexus.gateway.repository.SubscriptionRepository;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link SubscriptionServiceImpl} 单元测试：覆盖创建、扣款成功/失败、
 * 取消、定时任务与平台公钥缺失 fail-closed 分支。
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceImplTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SigningServiceFeignClient signingServiceClient;
    @Mock private WalletMgmtFeignClient walletMgmtClient;
    @Mock private WalletAddressHelper walletAddressHelper;

    private GatewayConfig cfg;
    private SubscriptionServiceImpl service;

    @BeforeEach
    void setUp() {
        cfg = new GatewayConfig();
        cfg.getExchangeWallet().setPlatformPubkey("platform-pubkey");
        // P0-4 审计修复：charge 改为"原子认领→转账"，认领经 TransactionTemplate 执行。
        // 单测中用 mock PlatformTransactionManager 直接放行事务边界（回调照常执行）。
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new SubscriptionServiceImpl(subscriptionRepository,
                signingServiceClient, walletMgmtClient, cfg, tm, walletAddressHelper);
    }

    @Test
    @DisplayName("createSubscription: 链上授权成功→落库 ACTIVE+真实authTxHash")
    void createSubscription_onChainAuthSuccess() {
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signingServiceClient.signTransfer("platform-pubkey", "payeeHash", BigDecimal.ZERO))
                .thenReturn("0xAuthTxHash");

        Subscription result = service.createSubscription(100L, "0xPayer", "0xPayee",
                new BigDecimal("1000"), 30);

        assertEquals(100L, result.getMerchantId());
        assertEquals(Subscription.SubscriptionStatus.ACTIVE, result.getStatus());
        assertEquals(0, result.getChargedCount());
        assertNotNull(result.getSubscriptionNo());
        assertEquals("0xAuthTxHash", result.getAuthTxHash(), "authTxHash应为链上真实交易哈希");
        assertNotNull(result.getNextChargeAt());
    }

    @Test
    @DisplayName("createSubscription: 钱包不可达→authTxHash=null（fail-closed）")
    void createSubscription_walletUnreachable() {
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn(null);

        Subscription result = service.createSubscription(100L, "0xPayer", "0xPayee",
                new BigDecimal("1000"), 30);

        assertNull(result.getAuthTxHash(), "钱包不可达时authTxHash应为null");
        assertEquals(Subscription.SubscriptionStatus.ACTIVE, result.getStatus(), "订阅仍应创建");
    }

    @Test
    @DisplayName("createSubscription: 平台公钥未配置→authTxHash=null（fail-closed）")
    void createSubscription_noPlatformPubkey() {
        cfg.getExchangeWallet().setPlatformPubkey("");
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");

        Subscription result = service.createSubscription(100L, "0xPayer", "0xPayee",
                new BigDecimal("1000"), 30);

        assertNull(result.getAuthTxHash(), "平台公钥未配置时authTxHash应为null");
    }

    @Test
    @DisplayName("createSubscription: 签名服务异常→authTxHash=null（fail-closed）")
    void createSubscription_signingException() {
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signingServiceClient.signTransfer(any(), any(), any())).thenThrow(new RuntimeException("sign err"));

        Subscription result = service.createSubscription(100L, "0xPayer", "0xPayee",
                new BigDecimal("1000"), 30);

        assertNull(result.getAuthTxHash(), "签名服务异常时authTxHash应为null");
        assertEquals(Subscription.SubscriptionStatus.ACTIVE, result.getStatus(), "订阅仍应创建");
    }

    @Test
    @DisplayName("findById: 委托 repository")
    void findById() {
        Subscription sub = new Subscription();
        sub.setId(1L);
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        assertTrue(service.findById(1L).isPresent());

        when(subscriptionRepository.findById(2L)).thenReturn(Optional.empty());
        assertFalse(service.findById(2L).isPresent());
    }

    @Test
    @DisplayName("charge: 认领成功 + wallet/signing 成功 -> 返回 txHash")
    void charge_success() {
        Subscription sub = activeSubscription(1L);
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.claimCharge(eq(1L), eq(Subscription.SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signingServiceClient.signTransfer("platform-pubkey", "payeeHash", new BigDecimal("1000")))
                .thenReturn("0xTxHash");

        String txHash = service.charge(1L);

        assertEquals("0xTxHash", txHash);
        verify(subscriptionRepository).claimCharge(eq(1L), eq(Subscription.SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("charge: 认领失败（未到期/已取消/被并发认领）-> 不发起转账")
    void charge_notClaimed_doesNotTransfer() {
        Subscription sub = activeSubscription(1L);
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        // mock 默认返回 0（未认领成功）
        assertNull(service.charge(1L));
        verify(subscriptionRepository).claimCharge(eq(1L), eq(Subscription.SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verifyNoInteractions(signingServiceClient);
    }

    @Test
    @DisplayName("charge: 不存在抛异常")
    void charge_notFound() {
        when(subscriptionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.charge(99L));
    }

    @Test
    @DisplayName("charge: 认领成功但 wallet 不可达返回 null（fail-closed，周期已消耗）")
    void charge_walletUnreachable() {
        Subscription sub = activeSubscription(1L);
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.claimCharge(eq(1L), eq(Subscription.SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn(null);

        assertNull(service.charge(1L));
    }

    @Test
    @DisplayName("charge: 认领成功但 platform pubkey 未配置返回 null（fail-closed）")
    void charge_noPlatformPubkey() {
        cfg.getExchangeWallet().setPlatformPubkey("");
        Subscription sub = activeSubscription(1L);
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.claimCharge(eq(1L), eq(Subscription.SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");

        assertNull(service.charge(1L));
    }

    @Test
    @DisplayName("charge: signing 抛异常返回 null")
    void charge_signingException() {
        Subscription sub = activeSubscription(1L);
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.claimCharge(eq(1L), eq(Subscription.SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signingServiceClient.signTransfer(any(), any(), any())).thenThrow(new RuntimeException("sign err"));

        assertNull(service.charge(1L));
    }

    @Test
    @DisplayName("cancel: 置 CANCELLED + cancelledAt")
    void cancel() {
        Subscription sub = activeSubscription(1L);
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = service.cancel(1L);
        assertEquals(Subscription.SubscriptionStatus.CANCELLED, result.getStatus());
        assertNotNull(result.getCancelledAt());
    }

    @Test
    @DisplayName("cancel: 不存在抛异常")
    void cancel_notFound() {
        when(subscriptionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.cancel(99L));
    }

    @Test
    @DisplayName("processDueSubscriptions: 多订阅扣款，返回成功数")
    void processDue_allSucceed() {
        Subscription s1 = activeSubscription(1L);
        Subscription s2 = activeSubscription(2L);
        when(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                eq(Subscription.SubscriptionStatus.ACTIVE), any()))
                .thenReturn(List.of(s1, s2));
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(s1));
        when(subscriptionRepository.findById(2L)).thenReturn(Optional.of(s2));
        when(subscriptionRepository.claimCharge(anyLong(), eq(Subscription.SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signingServiceClient.signTransfer(any(), any(), any())).thenReturn("0xTx");

        int count = service.processDueSubscriptions();
        assertEquals(2, count);
    }

    @Test
    @DisplayName("processDueSubscriptions: 空列表返回 0")
    void processDue_empty() {
        when(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                eq(Subscription.SubscriptionStatus.ACTIVE), any()))
                .thenReturn(List.of());
        assertEquals(0, service.processDueSubscriptions());
    }

    @Test
    @DisplayName("scheduledCharge: 委托 processDueSubscriptions（不抛异常即可）")
    void scheduledCharge() {
        when(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                eq(Subscription.SubscriptionStatus.ACTIVE), any()))
                .thenReturn(List.of());
        service.scheduledCharge();
    }

    private Subscription activeSubscription(Long id) {
        Subscription s = new Subscription();
        s.setId(id);
        s.setSubscriptionNo("SUB-1");
        s.setMerchantId(100L);
        s.setPayerAddress("0xPayer");
        s.setPayeeAddress("0xPayee");
        s.setAmount(new BigDecimal("1000"));
        s.setCycleDays(30);
        s.setChargedCount(0);
        s.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        s.setNextChargeAt(LocalDateTime.now().minusDays(1));
        return s;
    }
}
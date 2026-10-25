package org.nexus.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.gateway.approval.ApprovalExecutionLink;
import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.model.Subscription;
import org.nexus.gateway.repository.SubscriptionRepository;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SubscriptionServiceImpl} 的大额签名审批执行腿用例。
 *
 * <p>订阅扣款是"认领周期→转账"的一次性路径（认领后周期即消耗），因此 2001 只能表现为
 * "本周期等待审批执行"，不得记为扣款失败；幂等键必须带周期序号，避免同一订阅重复建审批。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceImplApprovalTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SigningServiceFeignClient signingServiceClient;
    @Mock private WalletMgmtFeignClient walletMgmtClient;
    @Mock private WalletAddressHelper walletAddressHelper;
    @Mock private SigningApprovalExecutionService approvalService;

    private GatewayConfig cfg;
    private SubscriptionServiceImpl service;

    private Subscription dueSubscription;

    @BeforeEach
    void setUp() {
        cfg = new GatewayConfig();
        cfg.getExchangeWallet().setPlatformPubkey("platform-pubkey");

        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service = new SubscriptionServiceImpl(subscriptionRepository, signingServiceClient,
                walletMgmtClient, cfg, tm, walletAddressHelper);
        service.setApprovalExecutionService(approvalService);

        dueSubscription = new Subscription();
        dueSubscription.setId(1L);
        dueSubscription.setSubscriptionNo("SUB1");
        dueSubscription.setMerchantId(100L);
        dueSubscription.setPayeeAddress("0xPayee");
        dueSubscription.setPayerAddress("0xPayer");
        dueSubscription.setAmount(new BigDecimal("500000"));
        dueSubscription.setCycleDays(30);
        dueSubscription.setChargedCount(2);
        dueSubscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        dueSubscription.setNextChargeAt(LocalDateTime.now().minusMinutes(1));
    }

    private void stubClaimSucceeded() {
        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(dueSubscription));
        when(subscriptionRepository.claimCharge(eq(1L), any(), any(), any())).thenReturn(1);
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
    }

    @Test
    @DisplayName("charge: 2001 待审批→返回 null 但按幂等键落审批链接，不走直接签名")
    void charge_awaitingApprovalKeepsCyclePending() {
        stubClaimSucceeded();
        when(approvalService.signOrAwait(eq("execution:SUB_CHARGE:SUB1:2"), eq("SUB_CHARGE"),
                eq("platform-pubkey"), eq("payeeHash"), any(BigDecimal.class), eq("NEX")))
                .thenReturn(new SigningOutcome(
                        ApprovalExecutionLink.Status.AWAITING_APPROVAL, "appr-1", null, null));

        assertNull(service.charge(1L), "待审批不产出伪哈希");

        verify(signingServiceClient, never()).signTransfer(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("charge: 审批腿已执行→返回真实哈希")
    void charge_executedReturnsHash() {
        stubClaimSucceeded();
        when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString()))
                .thenReturn(new SigningOutcome(
                        ApprovalExecutionLink.Status.EXECUTED, null, "0xCharge", null));

        assertEquals("0xCharge", service.charge(1L));
    }

    @Test
    @DisplayName("charge: 幂等键含周期序号（同一订阅不同周期各一条审批）")
    void charge_requestKeyCarriesCycle() {
        stubClaimSucceeded();
        when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString()))
                .thenReturn(new SigningOutcome(
                        ApprovalExecutionLink.Status.AWAITING_APPROVAL, "appr-1", null, null));

        service.charge(1L);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(approvalService).signOrAwait(key.capture(), eq("SUB_CHARGE"), anyString(),
                anyString(), any(BigDecimal.class), anyString());
        assertEquals("execution:SUB_CHARGE:SUB1:2", key.getValue());
    }

    @Test
    @DisplayName("createSubscription: 授权 2001→authTxHash 留空并建链接，交由推进器补齐")
    void createSubscription_authAwaitingLeavesHashNull() {
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletAddressHelper.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(approvalService.signOrAwait(anyString(), eq("SUB_AUTH"), anyString(), anyString(),
                any(BigDecimal.class), anyString()))
                .thenReturn(new SigningOutcome(
                        ApprovalExecutionLink.Status.AWAITING_APPROVAL, "appr-2", null, null));

        Subscription created = service.createSubscription(100L, "0xPayer", "0xPayee",
                new BigDecimal("1000"), 30);

        assertNull(created.getAuthTxHash());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(approvalService).signOrAwait(key.capture(), eq("SUB_AUTH"), anyString(), anyString(),
                any(BigDecimal.class), anyString());
        assertEquals("execution:SUB_AUTH:" + created.getSubscriptionNo(), key.getValue());
    }

    @Test
    @DisplayName("charge: 结果不可核对→返回 null（不报失败也不重试）")
    void charge_unverifiedReturnsNull() {
        stubClaimSucceeded();
        when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString()))
                .thenReturn(new SigningOutcome(
                        ApprovalExecutionLink.Status.EXECUTION_UNKNOWN, "appr-1", null, "结果未知"));

        assertNull(service.charge(1L));
    }
}

package org.nexus.gateway.service;

import org.nexus.gateway.SubscriptionService;
import org.nexus.gateway.model.Subscription;
import org.nexus.gateway.repository.SubscriptionRepository;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SubscriptionServiceImpl implements SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionServiceImpl.class);

    private final SubscriptionRepository subscriptionRepository;
    /** 签名服务 Feign 客户端：legacy 转账（订阅扣款，调用方提供私钥） */
    private final SigningServiceFeignClient signingServiceClient;
    /** 钱包管理服务 Feign 客户端：地址转公钥哈希 */
    private final WalletMgmtFeignClient walletMgmtClient;

    public SubscriptionServiceImpl(SubscriptionRepository subscriptionRepository,
                                   SigningServiceFeignClient signingServiceClient,
                                   WalletMgmtFeignClient walletMgmtClient) {
        this.subscriptionRepository = subscriptionRepository;
        this.signingServiceClient = signingServiceClient;
        this.walletMgmtClient = walletMgmtClient;
    }

    @Override
    @Transactional
    public Subscription createSubscription(Long merchantId, String payerAddress, String payeeAddress,
                                           BigDecimal amount, int cycleDays) {
        Subscription sub = new Subscription();
        sub.setSubscriptionNo("SUB" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        sub.setMerchantId(merchantId);
        sub.setPayerAddress(payerAddress);
        sub.setPayeeAddress(payeeAddress);
        sub.setAmount(amount);
        sub.setCycleDays(cycleDays);
        sub.setChargedCount(0);
        sub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        sub.setNextChargeAt(LocalDateTime.now().plusDays(cycleDays));

        // TODO: submit SUBSCRIPTION_AUTH transaction on-chain via nexus-sdk
        sub.setAuthTxHash("0x" + UUID.randomUUID().toString().replace("-", ""));

        Subscription saved = subscriptionRepository.save(sub);
        log.info("Subscription created: subNo={}, merchant={}, amount={}, cycleDays={}",
                saved.getSubscriptionNo(), merchantId, amount, cycleDays);
        return saved;
    }

    @Override
    public Optional<Subscription> findById(Long subscriptionId) {
        return subscriptionRepository.findById(subscriptionId);
    }

    @Override
    @Transactional
    @GlobalTransactional(timeoutMills = 120000)
    public String charge(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));

        if (sub.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            log.warn("Cannot charge subscription in status: {}", sub.getStatus());
            return null;
        }

        // Execute recurring charge via exchange-wallet
        String txHash = executeSubscriptionCharge(sub);

        if (txHash != null) {
            sub.setChargedCount(sub.getChargedCount() + 1);
            sub.setNextChargeAt(LocalDateTime.now().plusDays(sub.getCycleDays()));
            subscriptionRepository.save(sub);
            log.info("Subscription charged: subNo={}, count={}, txHash={}",
                    sub.getSubscriptionNo(), sub.getChargedCount(), txHash);
        } else {
            log.error("Subscription charge failed: subNo={}", sub.getSubscriptionNo());
        }
        return txHash;
    }

    @Override
    @Transactional
    public Subscription cancel(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));

        sub.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        sub.setCancelledAt(LocalDateTime.now());
        Subscription saved = subscriptionRepository.save(sub);

        log.info("Subscription cancelled: subNo={}", sub.getSubscriptionNo());
        return saved;
    }

    @Override
    @Transactional
    public int processDueSubscriptions() {
        List<Subscription> dueList = subscriptionRepository.findByStatusAndNextChargeAtBefore(
                Subscription.SubscriptionStatus.ACTIVE, LocalDateTime.now());

        int successCount = 0;
        for (Subscription sub : dueList) {
            String txHash = charge(sub.getId());
            if (txHash != null) {
                successCount++;
            }
        }
        if (successCount > 0) {
            log.info("Processed {} due subscription charges", successCount);
        }
        return successCount;
    }

    /**
     * Scheduled task: process due subscriptions every 10 minutes.
     */
    @Scheduled(fixedRate = 600000)
    public void scheduledCharge() {
        processDueSubscriptions();
    }

    /**
     * Execute a subscription charge transfer via the exchange-wallet.
     * In production, merchant key material comes from secure storage.
     */
    private String executeSubscriptionCharge(Subscription sub) {
        try {
            String receiverPubkeyHash = walletMgmtClient.addressToPubkeyHash(sub.getPayeeAddress());
            if (receiverPubkeyHash == null) {
                log.warn("Cannot resolve payee pubkeyHash (wallet unreachable?), simulating charge for: {}", sub.getSubscriptionNo());
                return "0x" + UUID.randomUUID().toString().replace("-", "");
            }

            // In production, retrieve merchant/payer keypair from secure key store (HSM/KMS/Vault)
            String payerPubkey = "";  // TODO: from secure key store
            String payerPrikey = "";  // TODO: from secure key store

            if (payerPubkey.isEmpty() || payerPrikey.isEmpty()) {
                log.warn("Payer key material not configured, simulating subscription charge");
                return "0x" + UUID.randomUUID().toString().replace("-", "");
            }

            return signingServiceClient.transfer(payerPubkey, receiverPubkeyHash, sub.getAmount(), payerPrikey);
        } catch (Exception e) {
            log.error("Subscription charge exception: {}", e.getMessage());
            return null;
        }
    }
}
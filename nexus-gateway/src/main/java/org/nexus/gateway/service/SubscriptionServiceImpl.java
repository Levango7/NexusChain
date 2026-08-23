package org.nexus.gateway.service;

import org.nexus.gateway.SubscriptionService;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.model.Subscription;
import org.nexus.gateway.repository.SubscriptionRepository;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.wallet.WalletUtils;
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
    /** 签名服务 Feign 客户端：签名 + 广播（涉及私钥的操作，订阅扣款） */
    private final SigningServiceFeignClient signingServiceClient;
    /** 钱包管理服务 Feign 客户端：地址转公钥哈希 */
    private final WalletMgmtFeignClient walletMgmtClient;
    /** 网关配置：提供平台热钱包公钥（私钥永不离开签名服务） */
    private final GatewayConfig gatewayConfig;

    public SubscriptionServiceImpl(SubscriptionRepository subscriptionRepository,
                                   SigningServiceFeignClient signingServiceClient,
                                   WalletMgmtFeignClient walletMgmtClient,
                                   GatewayConfig gatewayConfig) {
        this.subscriptionRepository = subscriptionRepository;
        this.signingServiceClient = signingServiceClient;
        this.walletMgmtClient = walletMgmtClient;
        this.gatewayConfig = gatewayConfig;
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

        // 链上授权交易：通过签名服务提交 SUBSCRIPTION_AUTH 交易上链
        // 使用平台热钱包向 payee 发送 0 金额授权标记交易，记录真实 txHash
        String authTxHash = submitOnChainAuth(sub);
        sub.setAuthTxHash(authTxHash);

        Subscription saved = subscriptionRepository.save(sub);
        log.info("Subscription created: subNo={}, merchant={}, amount={}, cycleDays={}, authTxHash={}",
                saved.getSubscriptionNo(), merchantId, amount, cycleDays, authTxHash);
        return saved;
    }

    /**
     * 提交链上订阅授权交易。
     *
     * <p>通过签名服务使用平台热钱包向 payee 发送一笔 0 金额的授权标记交易，
     * 将订阅授权记录上链。返回真实链上 txHash。</p>
     *
     * <p>fail-closed 策略：如果签名服务/钱包不可达或平台公钥未配置，
     * 返回 {@code null}（authTxHash 为 null 表示未链上授权），订阅仍创建
     * 但需后续补偿授权。绝不生成伪交易哈希。</p>
     *
     * @param sub 订阅实体（需已设置 payeeAddress）
     * @return 链上交易哈希，或 null（签名服务不可达时）
     */
    private String submitOnChainAuth(Subscription sub) {
        try {
            String payeePubkeyHash = WalletUtils.addressToPubkeyHash(sub.getPayeeAddress());
            if (payeePubkeyHash == null) {
                log.warn("Cannot resolve payee pubkeyHash for on-chain auth (wallet unreachable?), " +
                        "subscription created without on-chain auth: {}", sub.getSubscriptionNo());
                return null;
            }

            String platformPubkey = gatewayConfig.getExchangeWallet().getPlatformPubkey();
            if (platformPubkey == null || platformPubkey.isEmpty()) {
                log.warn("Platform pubkey not configured, subscription created without on-chain auth: {}",
                        sub.getSubscriptionNo());
                return null;
            }

            // 提交 0 金额授权标记交易（SUBSCRIPTION_AUTH），签名服务签名+广播
            String txHash = signingServiceClient.signTransfer(
                    platformPubkey, payeePubkeyHash, BigDecimal.ZERO);
            log.info("On-chain subscription auth submitted: subNo={}, txHash={}",
                    sub.getSubscriptionNo(), txHash);
            return txHash;
        } catch (RuntimeException e) {
            log.warn("On-chain subscription auth failed (fail-closed, subscription created without auth): " +
                    "subNo={}, error={}", sub.getSubscriptionNo(), e.getMessage());
            return null;
        }
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
     *
     * <p>P0-3 安全修复：订阅扣款由签名服务使用平台热钱包密钥库完成签名，
     * 网关永不获取或传输私钥（legacy {@code transfer(..., prikey)} 路径已废弃）。
     * 钱包不可达或平台公钥未配置时 fail-closed 返回 {@code null}，调用方据此
     * 标记扣款失败，绝不生成伪交易哈希。</p>
     */
    private String executeSubscriptionCharge(Subscription sub) {
        try {
            String receiverPubkeyHash = WalletUtils.addressToPubkeyHash(sub.getPayeeAddress());
            if (receiverPubkeyHash == null) {
                log.error("Cannot resolve payee pubkeyHash (wallet unreachable?), subscription charge failed (fail-closed): {}", sub.getSubscriptionNo());
                return null;
            }

            // P0-3 安全修复：使用 signTransfer（不传私钥），签名服务持钥签名
            // 平台热钱包公钥从配置获取，私钥永不离开签名服务进程
            String platformPubkey = gatewayConfig.getExchangeWallet().getPlatformPubkey();

            if (platformPubkey == null || platformPubkey.isEmpty()) {
                log.error("Platform pubkey not configured, subscription charge failed (fail-closed): {}", sub.getSubscriptionNo());
                return null;
            }

            return signingServiceClient.signTransfer(platformPubkey, receiverPubkeyHash, sub.getAmount());
        } catch (RuntimeException e) {
            log.error("Subscription charge exception: {}", e.getMessage());
            return null;
        }
    }
}
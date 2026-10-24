package org.nexus.gateway.service;

import org.nexus.gateway.SubscriptionService;
import org.nexus.gateway.approval.ApprovalExecutionLink;
import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.gateway.approval.SigningTransfers;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.model.Subscription;
import org.nexus.gateway.repository.SubscriptionRepository;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.wallet.WalletUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    /**
     * 扣款认领事务模板（P0-4 审计修复）。
     *
     * <p>REQUIRES_NEW：认领（条件 UPDATE）在独立短事务内立即提交，
     * 不与链上转账同事务——既避免行锁跨远程调用长期持有，
     * 又保证"认领先于转账"的先后顺序成立。</p>
     */
    private final TransactionTemplate claimTemplate;
    private final WalletAddressHelper walletAddressHelper;

    /**
     * 大额签名审批执行服务（可选装配）。装配后，订阅授权/扣款的大额转账会转
     * "待审批"并落库 approvalId；未装配时退化为直接签名（2001 fail-closed）。
     */
    private SigningApprovalExecutionService approvalExecutionService;

    @Autowired(required = false)
    public void setApprovalExecutionService(SigningApprovalExecutionService approvalExecutionService) {
        this.approvalExecutionService = approvalExecutionService;
    }

    public SubscriptionServiceImpl(SubscriptionRepository subscriptionRepository,
                                   SigningServiceFeignClient signingServiceClient,
                                   WalletMgmtFeignClient walletMgmtClient,
                                   GatewayConfig gatewayConfig,
                                   PlatformTransactionManager transactionManager,
                                   WalletAddressHelper walletAddressHelper) {
        this.subscriptionRepository = subscriptionRepository;
        this.signingServiceClient = signingServiceClient;
        this.walletMgmtClient = walletMgmtClient;
        this.gatewayConfig = gatewayConfig;
        this.claimTemplate = new TransactionTemplate(transactionManager);
        this.claimTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.walletAddressHelper = walletAddressHelper;
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
            String payeePubkeyHash = walletAddressHelper.addressToPubkeyHash(sub.getPayeeAddress());
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

            // 提交 0 金额普通转账作为订阅授权标记；注意（PLAN-014 Phase 3）：
            // 这里调用的是 signTransfer（TRANSFER 0x01），不是 SUBSCRIPTION_AUTH（0x1a）——
            // 0x1a 当前被节点 TransactionCheck 的 type<=15 门禁拒绝，不可上链。
            // 三态（执行腿）：已执行取 txHash；待审批/待人工核对时 authTxHash 留空，
            // 由 SubscriptionApprovalReconciler 在审批通过后补齐，绝不伪造哈希。
            SigningOutcome outcome = SigningTransfers.signOrAwait(approvalExecutionService,
                    signingServiceClient,
                    SigningTransfers.requestKey("SUB_AUTH", sub.getSubscriptionNo(), null),
                    "SUB_AUTH", platformPubkey, payeePubkeyHash, BigDecimal.ZERO, currencyOf(sub));
            if (outcome.isExecuted()) {
                log.info("On-chain subscription auth submitted: subNo={}, txHash={}",
                        sub.getSubscriptionNo(), outcome.txHash());
                return outcome.txHash();
            }
            if (outcome.isAwaiting()) {
                log.info("On-chain subscription auth awaiting approval: subNo={}, approvalId={}",
                        sub.getSubscriptionNo(), outcome.approvalId());
                return null;
            }
            if (outcome.requiresManualVerification()) {
                log.error("On-chain subscription auth executed but unverified (manual check required): "
                        + "subNo={}, approvalId={}", sub.getSubscriptionNo(), outcome.approvalId());
                return null;
            }
            log.warn("On-chain subscription auth signing failed (subscription created without auth): "
                    + "subNo={}, error={}", sub.getSubscriptionNo(), outcome.error());
            return null;
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

    /**
     * 执行一次订阅扣款（P0-4 审计修复：原子认领 → 链上转账）。
     *
     * <p>原实现的问题：先完成链上转账、后更新 chargedCount/nextChargeAt，
     * 实体无乐观锁、定时任务无分布式锁、且 {@code @GlobalTransactional} 因
     * processDueSubscriptions 自调用被代理绕过——并发/多实例下同一周期可被
     * 扣款多次。</p>
     *
     * <p>现语义：先用条件 UPDATE（{@code status=ACTIVE AND nextChargeAt<=now}）
     * 原子认领本周期（独立短事务立即提交），认领成功才执行链上转账。
     * 数据库行级原子性保证同一周期有且仅有一个认领成功，双重扣款在源头消除。</p>
     *
     * <p><b>失败语义（宁可漏收、不可双扣）</b>：认领后转账失败时本周期被消耗，
     * 记录 ERROR 日志供人工/补偿跟进，不自动回滚认领（回滚会在"转账超时但实际
     * 已广播"的场景下重新打开双扣窗口）。</p>
     *
     * @param subscriptionId 订阅 ID
     * @return 链上交易哈希；未认领（未到期/已取消/已扣）或转账失败返回 null
     */
    @Override
    public String charge(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextChargeAt = now.plusDays(sub.getCycleDays());
        Integer claimed = claimTemplate.execute(status ->
                subscriptionRepository.claimCharge(subscriptionId,
                        Subscription.SubscriptionStatus.ACTIVE, now, nextChargeAt));
        if (claimed == null || claimed == 0) {
            log.info("Subscription charge not claimed (not ACTIVE, not due, or already claimed): subNo={}",
                    sub.getSubscriptionNo());
            return null;
        }

        // 认领已提交，链上转账在事务外执行（不可逆操作不入事务）
        // 幂等键 = 订阅号 + 认领前的扣款次数：同一周期只会有一条审批请求，
        // 重试/重启后不会另起一笔重复扣款。
        String requestKey = SigningTransfers.requestKey("SUB_CHARGE",
                sub.getSubscriptionNo() + ":" + sub.getChargedCount(), null);
        SigningOutcome outcome = executeSubscriptionCharge(sub, requestKey);
        if (outcome.isExecuted()) {
            log.info("Subscription charged: subNo={}, count={}, txHash={}",
                    sub.getSubscriptionNo(), sub.getChargedCount() + 1, outcome.txHash());
            return outcome.txHash();
        }
        if (outcome.isAwaiting()) {
            log.info("Subscription charge awaiting approval (cycle consumed, execution leg will "
                    + "complete the transfer once approved): subNo={}, approvalId={}",
                    sub.getSubscriptionNo(), outcome.approvalId());
            return null;
        }
        if (outcome.requiresManualVerification()) {
            log.error("Subscription charge executed but result unverified (manual check required, "
                    + "do NOT retry): subNo={}, approvalId={}",
                    sub.getSubscriptionNo(), outcome.approvalId());
            return null;
        }
        log.error("Subscription charge transfer failed after claim (cycle consumed, "
                + "manual follow-up required): subNo={}, error={}",
                sub.getSubscriptionNo(), outcome.error());
        return null;
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

    /**
     * 处理到期订阅扣款（P0-4 审计修复：去掉包裹整个循环的大事务）。
     *
     * <p>每笔扣款由 {@link #charge} 内部的独立认领事务自行保证原子性；
     * 循环本身无需事务（原 @Transactional 使单笔失败回滚全部计数，
     * 而已转账的资金无法随数据库回滚）。</p>
     */
    @Override
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
     *
     * <p>P0-4 审计修复：增加 ShedLock 分布式锁（与 ReconciliationTask 同一
     * JdbcTemplateLockProvider 机制），多实例部署时同一时刻仅一个实例执行扣款扫描；
     * 实例间的残余并发由 {@link #charge} 的数据库级原子认领兜底。</p>
     */
    @Scheduled(fixedRate = 600000)
    @SchedulerLock(name = "subscriptionCharge", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
    public void scheduledCharge() {
        processDueSubscriptions();
    }

    /**
     * Execute a subscription charge transfer via the exchange-wallet.
     *
     * <p>P0-3 安全修复：订阅扣款由签名服务使用平台热钱包密钥库完成签名，
     * 网关永不获取或传输私钥（legacy {@code transfer(..., prikey)} 路径已废弃）。
     * 钱包不可达或平台公钥未配置时 fail-closed 返回失败结果，绝不生成伪交易哈希。</p>
     *
     * <p>执行腿（三态）：大额扣款返回 2001 时不再当失败——approvalId 落
     * {@code approval_execution_link}，由 {@code SubscriptionApprovalReconciler}
     * 在审批通过后执行。结果不可核对时同样不报失败，转人工核对（禁止自动重试）。</p>
     */
    private SigningOutcome executeSubscriptionCharge(Subscription sub, String requestKey) {
        try {
            String receiverPubkeyHash = walletAddressHelper.addressToPubkeyHash(sub.getPayeeAddress());
            if (receiverPubkeyHash == null) {
                log.error("Cannot resolve payee pubkeyHash (wallet unreachable?), subscription charge failed (fail-closed): {}", sub.getSubscriptionNo());
                return new SigningOutcome(ApprovalExecutionLink.Status.FAILED, null, null,
                        "payee pubkeyHash unavailable");
            }

            // P0-3 安全修复：使用 signTransfer（不传私钥），签名服务持钥签名
            // 平台热钱包公钥从配置获取，私钥永不离开签名服务进程
            String platformPubkey = gatewayConfig.getExchangeWallet().getPlatformPubkey();

            if (platformPubkey == null || platformPubkey.isEmpty()) {
                log.error("Platform pubkey not configured, subscription charge failed (fail-closed): {}", sub.getSubscriptionNo());
                return new SigningOutcome(ApprovalExecutionLink.Status.FAILED, null, null,
                        "platform pubkey not configured");
            }

            return SigningTransfers.signOrAwait(approvalExecutionService, signingServiceClient,
                    requestKey, "SUB_CHARGE", platformPubkey, receiverPubkeyHash,
                    sub.getAmount(), currencyOf(sub));
        } catch (RuntimeException e) {
            log.error("Subscription charge exception: {}", e.getMessage());
            return new SigningOutcome(ApprovalExecutionLink.Status.FAILED, null, null, e.getMessage());
        }
    }

    /** 订阅结算币种（tokenSymbol 缺失时回落 NEX），用于审批请求上的可读信息。 */
    private static String currencyOf(Subscription sub) {
        String symbol = sub.getTokenSymbol();
        return symbol == null || symbol.isBlank() ? "NEX" : symbol;
    }
}
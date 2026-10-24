package org.nexus.gateway.approval;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.nexus.gateway.model.Subscription;
import org.nexus.gateway.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 审批执行腿推进器（2026-10-25，Board #213 裁决"调用方执行"）。
 *
 * <p>大额签名返回 {@code 2001} 后，调用方把 approvalId 落
 * {@link ApprovalExecutionLink}，业务侧进入"待审批"而非 FAILED。审批通过后必须有人
 * 调用执行端点，否则资金永远不动。本类只覆盖<b>没有轮询调用方</b>的业务类型：</p>
 *
 * <ul>
 *   <li>{@code SUB_AUTH}——订阅链上授权（授权哈希由本类回写）；</li>
 *   <li>{@code SUB_CHARGE}——订阅扣款（扣款周期在认领时已消耗，本类只负责把已批准的
 *       那笔转出去，认领计数不再变化）；</li>
 *   <li>{@code CHAIN_REFUND} / {@code CONSORTIUM_REFUND}——连接器退款
 *       （连接器侧无退款状态查询接口，返回 pending 后无人再问）。</li>
 * </ul>
 *
 * <p><b>为什么 {@code CHAIN} / {@code CONSORTIUM} 支付不在白名单里</b>：这两类由
 * 该笔支付自己的状态轮询（{@code ChainConnector#queryPayment}）推进。若这里也自动
 * 扫，就可能出现"编排侧已把支付判 FAILED，后台随后把钱转走"——正是裁决 §六 禁止的
 * 形态。宁可让孤立审批停在待审批并留痕，也不制造双花面。</p>
 *
 * <p>结果不可核对（{@code EXECUTED_UNVERIFIED} / {@code EXECUTION_UNKNOWN}）时只告警，
 * 禁止自动重试：签名服务会把超时未完成的 EXECUTING 回退成 APPROVED，重复执行会双花。</p>
 *
 * <p>多副本：ShedLock 串行化扫描；链接表乐观锁 + 签名服务 APPROVED→EXECUTING 的 CAS
 * 保证同一审批只有一个执行者。</p>
 */
@Component
public class ApprovalExecutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExecutionReconciler.class);

    /** 本类托管的业务类型（无轮询调用方的那几类），见类注释。 */
    static final List<String> MANAGED_REQUEST_TYPES =
            List.of("SUB_AUTH", "SUB_CHARGE", "CHAIN_REFUND", "CONSORTIUM_REFUND");

    /** 刚由请求线程写入的链接先让出一轮，避免与调用方自身的推进竞争。 */
    private static final Duration SETTLE_BEFORE = Duration.ofSeconds(30);

    private static final List<ApprovalExecutionLink.Status> OPEN_STATUSES = List.of(
            ApprovalExecutionLink.Status.AWAITING_APPROVAL,
            ApprovalExecutionLink.Status.APPROVED,
            ApprovalExecutionLink.Status.EXECUTING);

    private static final int BATCH_LIMIT = 100;

    private final ApprovalExecutionLinkRepository linkRepository;
    private final SigningApprovalExecutionService signingApprovalExecutionService;
    private final SubscriptionRepository subscriptionRepository;
    private final TransactionTemplate transactionTemplate;

    public ApprovalExecutionReconciler(ApprovalExecutionLinkRepository linkRepository,
                                       SigningApprovalExecutionService signingApprovalExecutionService,
                                       SubscriptionRepository subscriptionRepository,
                                       PlatformTransactionManager transactionManager) {
        this.linkRepository = linkRepository;
        this.signingApprovalExecutionService = signingApprovalExecutionService;
        this.subscriptionRepository = subscriptionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 每 2 分钟推进一批托管类型的待审批链接。
     *
     * <p>cron 可用 {@code nexus.gateway.approval.execution-cron} 覆盖。</p>
     */
    @Scheduled(cron = "${nexus.gateway.approval.execution-cron:0 */2 * * * *}")
    @SchedulerLock(name = "approvalExecutionAdvance", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void advanceAwaitingApprovals() {
        List<ApprovalExecutionLink> candidates = linkRepository
                .findTop100ByRequestTypeInAndStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        MANAGED_REQUEST_TYPES, OPEN_STATUSES,
                        LocalDateTime.now().minus(SETTLE_BEFORE));
        if (candidates.isEmpty()) {
            return;
        }

        int executed = 0;
        int failed = 0;
        int manual = 0;
        int waiting = 0;
        for (ApprovalExecutionLink link : candidates.subList(0, Math.min(candidates.size(), BATCH_LIMIT))) {
            try {
                SigningOutcome outcome = signingApprovalExecutionService.refresh(link.getRequestKey());
                if (outcome.requiresManualVerification()) {
                    manual++;
                    log.error("审批执行结果不可核对，禁止自动重试，需人工核对链上交易: requestKey={}, status={}",
                            link.getRequestKey(), outcome.status());
                } else if (outcome.isExecuted()) {
                    executed++;
                    applyExecuted(link, outcome);
                } else if (outcome.isFailed()) {
                    failed++;
                    log.warn("审批执行腿明确失败（确定未动账）: requestKey={}, error={}",
                            link.getRequestKey(), outcome.error());
                } else {
                    waiting++;
                }
            } catch (RuntimeException e) {
                // 乐观锁冲突/暂时性异常：链接状态未变，下一轮再推进。
                log.warn("推进审批执行腿失败（不影响其他链接）: requestKey={}, error={}",
                        link.getRequestKey(), e.getMessage());
            }
        }
        log.info("审批执行腿推进完成: candidates={}, executed={}, failed={}, manual={}, waiting={}",
                candidates.size(), executed, failed, manual, waiting);
    }

    /**
     * 执行成功后的业务回写。
     *
     * <p>只有订阅授权需要回写（authTxHash 之前留空）；扣款周期的计数在认领时已推进，
     * 连接器退款没有业务表可写，因此仅记日志。</p>
     */
    private void applyExecuted(ApprovalExecutionLink link, SigningOutcome outcome) {
        if (!"SUB_AUTH".equals(link.getRequestType())) {
            log.info("审批执行完成: requestType={}, requestKey={}, txHash={}",
                    link.getRequestType(), link.getRequestKey(), outcome.txHash());
            return;
        }
        String subscriptionNo = link.businessId();
        String txHash = outcome.txHash();
        if (txHash == null || txHash.isBlank()) {
            log.error("订阅授权 EXECUTED 但无交易哈希，需人工核对: requestKey={}", link.getRequestKey());
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            Optional<Subscription> found = subscriptionRepository.findBySubscriptionNo(subscriptionNo);
            if (found.isEmpty()) {
                log.warn("订阅授权执行完成但找不到订阅: subscriptionNo={}", subscriptionNo);
                return;
            }
            Subscription sub = found.get();
            if (sub.getAuthTxHash() != null && !sub.getAuthTxHash().isBlank()) {
                return;
            }
            sub.setAuthTxHash(txHash);
            subscriptionRepository.save(sub);
            log.info("订阅链上授权补齐: subNo={}, txHash={}", subscriptionNo, txHash);
        });
    }
}

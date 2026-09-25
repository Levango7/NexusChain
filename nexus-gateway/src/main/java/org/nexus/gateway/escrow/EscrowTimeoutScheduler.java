package org.nexus.gateway.escrow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 担保交易/预授权超时调度器。
 *
 * <p>每 5 分钟扫描超时的担保交易和预授权：</p>
 * <ul>
 *   <li>担保交易超时：FUNDED 状态超过 autoConfirmDays → 自动确认收货（confirmEscrow）</li>
 *   <li>预授权超时：AUTHORIZED 状态超过 autoReleaseDays → 自动释放（voidPreAuth）</li>
 * </ul>
 *
 * <p>通过状态校验保证幂等：只有 FUNDED/AUTHORIZED 状态才会被处理，
 * 已变更状态的交易会被跳过。</p>
 */
@Component
public class EscrowTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(EscrowTimeoutScheduler.class);

    /** 默认超时自动确认天数（与 EscrowTransaction.autoConfirmDays 默认值一致） */
    private static final int DEFAULT_AUTO_CONFIRM_DAYS = 7;
    /** 默认超时自动释放天数（与 PreAuthTransaction.autoReleaseDays 默认值一致） */
    private static final int DEFAULT_AUTO_RELEASE_DAYS = 3;

    private final EscrowTransactionRepository escrowRepository;
    private final PreAuthTransactionRepository preauthRepository;
    private final EscrowService escrowService;
    private final PreAuthService preAuthService;

    public EscrowTimeoutScheduler(EscrowTransactionRepository escrowRepository,
                                   PreAuthTransactionRepository preauthRepository,
                                   EscrowService escrowService,
                                   PreAuthService preAuthService) {
        this.escrowRepository = escrowRepository;
        this.preauthRepository = preauthRepository;
        this.escrowService = escrowService;
        this.preAuthService = preAuthService;
    }

    /**
     * 担保交易超时自动确认 — 每 5 分钟扫描。
     *
     * <p>查找 FUNDED 状态且付款时间超过 autoConfirmDays 的担保交易，
     * 自动调用 confirmEscrow() 确认收货并释放资金给商户。</p>
     */
    @Scheduled(fixedDelay = 300000) // 5 分钟
    public void autoConfirmEscrow() {
        // M-5-fix: 改用 Repository 超时查询方法，避免加载所有 FUNDED 记录到内存
        LocalDateTime cutoff = LocalDateTime.now().minusDays(DEFAULT_AUTO_CONFIRM_DAYS);
        List<EscrowTransaction> timedOut = escrowRepository.findByStatusAndFundedAtBefore(
                EscrowStatus.FUNDED, cutoff);

        int processed = 0;
        for (EscrowTransaction escrow : timedOut) {
            try {
                escrowService.confirmEscrow(escrow.getEscrowNo());
                processed++;
                log.info("担保交易超时自动确认: escrowNo={}, fundedAt={}, autoConfirmDays={}",
                        escrow.getEscrowNo(), escrow.getFundedAt(), escrow.getAutoConfirmDays());
            } catch (Exception e) {
                log.error("担保交易超时自动确认失败: escrowNo={}", escrow.getEscrowNo(), e);
            }
        }

        if (processed > 0) {
            log.info("担保交易超时自动确认完成: 共处理 {} 笔", processed);
        }
    }

    /**
     * 预授权超时自动释放 — 每 5 分钟扫描。
     *
     * <p>查找 AUTHORIZED 状态且授权时间超过 autoReleaseDays 的预授权，
     * 自动调用 voidPreAuth() 释放冻结金额。</p>
     */
    @Scheduled(fixedDelay = 300000) // 5 分钟
    public void autoReleasePreAuth() {
        // M-5-fix: 改用 Repository 超时查询方法，避免加载所有 AUTHORIZED 记录到内存
        LocalDateTime cutoff = LocalDateTime.now().minusDays(DEFAULT_AUTO_RELEASE_DAYS);
        List<PreAuthTransaction> timedOut = preauthRepository.findByStatusAndAuthorizedAtBefore(
                PreAuthStatus.AUTHORIZED, cutoff);

        int processed = 0;
        for (PreAuthTransaction preauth : timedOut) {
            try {
                preAuthService.voidPreAuth(preauth.getPreauthNo());
                processed++;
                log.info("预授权超时自动释放: preauthNo={}, authorizedAt={}, autoReleaseDays={}",
                        preauth.getPreauthNo(), preauth.getAuthorizedAt(), preauth.getAutoReleaseDays());
            } catch (Exception e) {
                log.error("预授权超时自动释放失败: preauthNo={}", preauth.getPreauthNo(), e);
            }
        }

        if (processed > 0) {
            log.info("预授权超时自动释放完成: 共处理 {} 笔", processed);
        }
    }
}
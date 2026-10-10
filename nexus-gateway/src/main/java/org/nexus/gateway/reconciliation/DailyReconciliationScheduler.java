package org.nexus.gateway.reconciliation;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.nexus.gateway.reconciliation.bill.BillDownloadProxy;
import org.nexus.gateway.reconciliation.bill.BillDownloadResult;
import org.nexus.gateway.repository.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 日终对账调度器（P0 修复 2026-10-09）。
 *
 * <p><b>背景：</b>对账能力（账单下载、解析、比对、差错分类、自动调账、挂账核销）
 * 此前实现完整，但 {@code runDailyReconciliation} 只有单测调用方——
 * <b>没有任何生产触发点</b>：下载对账单只有人工 REST（{@code POST /api/v1/reconciliation/bills/download}），
 * 日终对账永远不会自动跑。本调度器补上"自动触发"这一环，形成完整闭环：</p>
 *
 * <pre>
 *   @Scheduled（本类）→ 拉取渠道对账单（BillDownloadProxy）
 *     → runDailyReconciliation（比对 + 差错落库）
 *     → ReconciliationDiffReportEvent（本批新增发布点）
 *     → ReconciliationAdjustmentListener（自动调账 / 建待审批）
 *     → DiscrepancyResolvedEvent → 挂账核销
 * </pre>
 *
 * <h3>失败语义（fail-closed）</h3>
 * <ul>
 *   <li>渠道未启用 / dry-run / 下载失败（对账单为空）→ 跳过该商户该渠道并记 WARN，
 *       <b>绝不</b>用空对账单去"对账"（会把全部内部交易误判为单边长款）；</li>
 *   <li>单商户/单渠道异常被捕获，不影响其余商户（对账是日终批处理，一个商户的
 *       凭证问题不应阻断全量对账）；</li>
 *   <li>整体 enabled 开关关闭 → 直接返回（默认开启：对账是资金安全动作）。</li>
 * </ul>
 *
 * <h3>多副本安全</h3>
 * <p>ShedLock 保证同一时刻仅一个实例执行（{@code lockAtMostFor=PT30M}）。</p>
 *
 * <h3>配置</h3>
 * <pre>
 * nexus.reconciliation.daily:
 *   enabled: true
 *   cron: "0 30 2 * * *"      # 每日 02:30（渠道对账单通常凌晨可用）
 *   channels: WECHAT,ALIPAY   # 参与自动对账的渠道
 * </pre>
 *
 * <p>商户列表取 {@code merchants} 表全量（与手工下载端点同口径——渠道凭证是
 * 部署级配置，对账按商户 ID 记录归属）。</p>
 */
@Component
public class DailyReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReconciliationScheduler.class);

    private final BillDownloadProxy billDownloadProxy;
    private final ReconciliationFileService reconciliationFileService;
    private final MerchantRepository merchantRepository;

    @Value("${nexus.reconciliation.daily.enabled:true}")
    private boolean enabled;

    /** 参与自动对账的渠道（逗号分隔），默认微信 + 支付宝。 */
    @Value("${nexus.reconciliation.daily.channels:WECHAT,ALIPAY}")
    private String channelsCsv;

    public DailyReconciliationScheduler(BillDownloadProxy billDownloadProxy,
                                         ReconciliationFileService reconciliationFileService,
                                         MerchantRepository merchantRepository) {
        this.billDownloadProxy = billDownloadProxy;
        this.reconciliationFileService = reconciliationFileService;
        this.merchantRepository = merchantRepository;
    }

    /**
     * 每日自动对账：对前一自然日的渠道对账单与内部交易记录做全量比对。
     *
     * <p>时区：使用 JVM 默认时区（部署侧统一 {@code TZ=Asia/Shanghai}，
     * 与渠道账单日期口径一致）。</p>
     */
    @Scheduled(cron = "${nexus.reconciliation.daily.cron:0 30 2 * * *}")
    @SchedulerLock(name = "dailyReconciliationScheduler",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runDailyReconciliation() {
        if (!enabled) {
            log.info("[DailyReconciliation] 已禁用（nexus.reconciliation.daily.enabled=false），跳过");
            return;
        }

        LocalDate billDate = LocalDate.now().minusDays(1);
        List<String> channels = parseChannels();
        if (channels.isEmpty()) {
            log.warn("[DailyReconciliation] 无参与渠道（nexus.reconciliation.daily.channels 为空），跳过");
            return;
        }

        List<Long> merchantIds = merchantRepository.findAll().stream()
                .map(m -> m.getId())
                .filter(java.util.Objects::nonNull)
                .toList();

        log.info("[DailyReconciliation] 日终对账启动: billDate={}, channels={}, merchants={}",
                billDate, channels, merchantIds.size());

        int ok = 0;
        int skipped = 0;
        int failed = 0;

        for (Long merchantId : merchantIds) {
            for (String channel : channels) {
                try {
                    BillDownloadResult download =
                            billDownloadProxy.downloadBill(channel, merchantId, billDate.toString());
                    if (download == null || !download.isSuccess()
                            || download.getRawContent() == null || download.getRawContent().isBlank()) {
                        // fail-closed：不用空对账单做比对（否则全部内部交易被判单边长款）
                        log.warn("[DailyReconciliation] 对账单不可用，跳过: merchantId={}, channel={}, date={}, error={}",
                                merchantId, channel, billDate,
                                download != null ? download.getErrorMessage() : "download=null");
                        skipped++;
                        continue;
                    }

                    ReconciliationDiffReport report = reconciliationFileService.runDailyReconciliation(
                            merchantId, download.getRawContent(), billDate);
                    ok++;
                    log.info("[DailyReconciliation] 对账完成: merchantId={}, channel={}, date={}, "
                                    + "matched={}, discrepancies={}, dryRun={}",
                            merchantId, channel, billDate, report.getMatchedCount(),
                            report.getDiscrepancies() != null ? report.getDiscrepancies().size() : 0,
                            download.isDryRun());
                } catch (RuntimeException e) {
                    // 单商户单渠道失败不阻断其余（批量对账语义）
                    failed++;
                    log.error("[DailyReconciliation] 对账异常: merchantId={}, channel={}, date={}, error={}",
                            merchantId, channel, billDate, e.getMessage(), e);
                }
            }
        }

        log.info("[DailyReconciliation] 日终对账结束: billDate={}, ok={}, skipped={}, failed={}",
                billDate, ok, skipped, failed);
    }

    private List<String> parseChannels() {
        if (channelsCsv == null || channelsCsv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(channelsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

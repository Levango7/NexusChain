package org.nexus.gateway.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 租户级计费服务（P4-T6 多租户改造）。
 *
 * <p>按租户的 {@link TenantConfig#getFeeRateBps()} 计算手续费，并累加到
 * {@link TenantUsageRecord} 用于计费报表。手续费 = 金额 × feeRateBps / 10000，
 * 向下取整（对平台有利的最小化误差策略）。</p>
 *
 * <p>计费时点：支付确认（PAID）时调用 {@link #recordUsage} 记入当期。
 * 计费周期：按月聚合（period 格式 yyyy-MM）。</p>
 */
@Service
public class TenantBillingService {

    private static final Logger log = LoggerFactory.getLogger(TenantBillingService.class);
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final BigDecimal BPS_DIVISOR = new BigDecimal("10000");

    private final TenantRepository tenantRepository;
    private final TenantUsageRecordRepository usageRecordRepository;
    private final int defaultFeeRateBps;

    public TenantBillingService(TenantRepository tenantRepository,
                                 TenantUsageRecordRepository usageRecordRepository,
                                 @Value("${nexus.tenant.default-fee-rate-bps:100}") int defaultFeeRateBps) {
        this.tenantRepository = tenantRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.defaultFeeRateBps = defaultFeeRateBps;
    }

    /**
     * 计算单笔交易的手续费。
     *
     * @param tenantId 租户 ID
     * @param amount   交易金额（最小单位）
     * @return 手续费（最小单位，向下取整）
     */
    public BigDecimal calculateFee(String tenantId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        int bps = resolveFeeRateBps(tenantId);
        // fee = amount * bps / 10000，向下取整
        return amount.multiply(BigDecimal.valueOf(bps))
                .divide(BPS_DIVISOR, 0, RoundingMode.DOWN);
    }

    /**
     * 计算单笔交易的手续费（直接传入费率，跳过租户查询，用于测试/批量场景）。
     *
     * @param feeRateBps 费率（基点）
     * @param amount     交易金额
     * @return 手续费
     */
    public BigDecimal calculateFee(int feeRateBps, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(BigDecimal.valueOf(feeRateBps))
                .divide(BPS_DIVISOR, 0, RoundingMode.DOWN);
    }

    /**
     * 记录一笔已确认交易到租户使用量。
     *
     * <p>累加到当期 {@link TenantUsageRecord}：transactionCount +1，
     * totalAmount += amount，totalFee += fee。若当期记录不存在则创建。</p>
     *
     * @param tenantId 租户 ID
     * @param amount   交易金额
     * @param fee      手续费（由 {@link #calculateFee} 计算）
     * @param when     交易时间（用于确定计费周期）
     * @return 更新后的使用量记录
     */
    @Transactional
    public TenantUsageRecord recordUsage(String tenantId, BigDecimal amount, BigDecimal fee,
                                          LocalDateTime when) {
        String period = (when != null ? when : LocalDateTime.now()).format(PERIOD_FMT);
        BigDecimal safeAmount = amount != null ? amount : BigDecimal.ZERO;
        BigDecimal safeFee = fee != null ? fee : BigDecimal.ZERO;

        TenantUsageRecord record = usageRecordRepository
                .findByTenantIdAndPeriod(tenantId, period)
                .orElseGet(() -> {
                    TenantUsageRecord r = new TenantUsageRecord();
                    r.setTenantId(tenantId);
                    r.setPeriod(period);
                    r.setTransactionCount(0L);
                    r.setTotalAmount(BigDecimal.ZERO);
                    r.setTotalFee(BigDecimal.ZERO);
                    return r;
                });

        record.setTransactionCount(record.getTransactionCount() + 1);
        record.setTotalAmount(record.getTotalAmount().add(safeAmount));
        record.setTotalFee(record.getTotalFee().add(safeFee));
        TenantUsageRecord saved = usageRecordRepository.save(record);
        log.debug("Recorded usage: tenantId={}, period={}, txCount={}, amount={}, fee={}",
                tenantId, period, saved.getTransactionCount(), safeAmount, safeFee);
        return saved;
    }

    /**
     * 查询租户在指定周期的使用量。
     *
     * @param tenantId 租户 ID
     * @param period   周期（yyyy-MM）
     * @return 使用量记录
     */
    public Optional<TenantUsageRecord> getUsage(String tenantId, String period) {
        return usageRecordRepository.findByTenantIdAndPeriod(tenantId, period);
    }

    /**
     * 解析租户费率（bps），租户未配置或不存在时返回默认费率。
     */
    private int resolveFeeRateBps(String tenantId) {
        if (tenantId == null) {
            return defaultFeeRateBps;
        }
        return tenantRepository.findByTenantId(tenantId)
                .map(Tenant::getConfig)
                .map(TenantConfig::getFeeRateBps)
                .filter(bps -> bps != null && bps >= 0)
                .orElse(defaultFeeRateBps);
    }
}
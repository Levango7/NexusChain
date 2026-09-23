package org.nexus.gateway.sla;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * SLA 监控服务。
 *
 * <p>定时（每5分钟）计算各 SlaTarget 的实际指标值，从 MeterRegistry 获取 Micrometer 指标数据，
 * 按 AVAILABILITY/LATENCY/THROUGHPUT/ERROR_RATE 四种类型分别计算，创建 SlaMeasurement 记录
 * 并持久化。未达标时通过 ApplicationEventPublisher 发布 SlaBreachEvent。</p>
 */
@Service
public class SlaMonitorService {

    private static final Logger log = LoggerFactory.getLogger(SlaMonitorService.class);

    private final SlaTargetRepository targetRepository;
    private final SlaMeasurementRepository measurementRepository;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public SlaMonitorService(SlaTargetRepository targetRepository,
                             SlaMeasurementRepository measurementRepository,
                             MeterRegistry meterRegistry,
                             ApplicationEventPublisher eventPublisher) {
        this.targetRepository = targetRepository;
        this.measurementRepository = measurementRepository;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 定时执行 SLA 监控检查。默认每 5 分钟执行一次。
     */
    @Scheduled(fixedDelayString = "${nexus.sla.monitor.check-interval-seconds:300}000")
    @Transactional
    public void monitor() {
        log.debug("SLA 监控检查开始");
        List<SlaTarget> targets = targetRepository.findByEnabledTrue();
        if (targets.isEmpty()) {
            log.debug("无已启用的 SLA 目标，跳过监控检查");
            return;
        }

        for (SlaTarget target : targets) {
            try {
                SlaMeasurement measurement = measure(target);
                measurementRepository.save(measurement);
                log.debug("SLA 目标 [{}] 测量完成: 实际值={}, 目标值={}, 达标={}",
                        target.getName(), measurement.getMeasuredValue(),
                        measurement.getTargetValue(), measurement.isMet());

                if (!measurement.isMet()) {
                    eventPublisher.publishEvent(new SlaBreachEvent(this, target, measurement));
                    log.warn("SLA 违约: 目标 [{}] 未达标，实际值={}, 目标值={}",
                            target.getName(), measurement.getMeasuredValue(),
                            measurement.getTargetValue());
                }
            } catch (Exception e) {
                log.error("SLA 目标 [{}] 测量失败: {}", target.getName(), e.getMessage(), e);
            }
        }
        log.debug("SLA 监控检查完成，共处理 {} 个目标", targets.size());
    }

    /**
     * 对单个 SLA 目标执行测量。
     *
     * @param target SLA 目标定义
     * @return 测量结果记录
     */
    public SlaMeasurement measure(SlaTarget target) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes(target.getWindowMinutes());

        double measuredValue = calculateMetricValue(target);
        boolean isMet = evaluateMet(target, measuredValue);

        SlaMeasurement measurement = new SlaMeasurement();
        measurement.setTargetId(target.getId());
        measurement.setMeasuredValue(measuredValue);
        measurement.setTargetValue(target.getTargetValue());
        measurement.setMet(isMet);
        measurement.setMeasuredAt(now);
        measurement.setWindowStart(windowStart);
        measurement.setWindowEnd(now);

        return measurement;
    }

    /**
     * 根据 SLA 目标类型计算实际指标值。
     */
    private double calculateMetricValue(SlaTarget target) {
        return switch (target.getTargetType()) {
            case AVAILABILITY -> calculateAvailability(target);
            case LATENCY -> calculateLatency(target);
            case THROUGHPUT -> calculateThroughput(target);
            case ERROR_RATE -> calculateErrorRate(target);
        };
    }

    /**
     * AVAILABILITY: (成功请求数 / 总请求数) * 100
     *
     * <p>指标命名约定：metricName 为基础指标名（如 "nexus.payments"），
     * 成功计数器为 metricName + ".confirmed"，失败计数器为 metricName + ".failed"。</p>
     */
    private double calculateAvailability(SlaTarget target) {
        String successMetric = target.getMetricName() + ".confirmed";
        String failureMetric = target.getMetricName() + ".failed";

        double successCount = getCounterValue(successMetric);
        double failureCount = getCounterValue(failureMetric);
        double totalCount = successCount + failureCount;

        if (totalCount == 0) {
            log.debug("AVAILABILITY 计算: 无请求数据，返回 100.0（无请求视为可用）");
            return 100.0;
        }

        return (successCount / totalCount) * 100.0;
    }

    /**
     * LATENCY: 从 Timer 指标获取 P99 延迟值（毫秒）
     *
     * <p>metricName 直接对应 Timer 注册名（如 "nexus.payment.latency"），
     * 取 P99 百分位作为延迟指标。</p>
     */
    private double calculateLatency(SlaTarget target) {
        Timer timer = meterRegistry.find(target.getMetricName()).timer();
        if (timer == null) {
            log.debug("LATENCY 计算: 未找到 Timer 指标 [{}]，返回 0", target.getMetricName());
            return 0.0;
        }

        HistogramSnapshot snapshot = timer.takeSnapshot();
        ValueAtPercentile[] percentiles = snapshot.percentileValues();
        if (percentiles == null || percentiles.length == 0) {
            // 无百分位数据时，使用 max 值作为延迟指标
            double maxNanos = snapshot.max();
            return TimeUnit.NANOSECONDS.toMillis((long) maxNanos);
        }

        // 查找 P99 百分位值
        double p99Nanos = 0.0;
        for (ValueAtPercentile v : percentiles) {
            if (v.percentile() >= 0.99) {
                p99Nanos = v.value();
                break;
            }
        }
        // 若未找到 P99，取最后一个（最高）百分位值
        if (p99Nanos == 0.0 && percentiles.length > 0) {
            p99Nanos = percentiles[percentiles.length - 1].value();
        }

        // Micrometer Timer 百分位值默认单位为纳秒，转换为毫秒
        return TimeUnit.NANOSECONDS.toMillis((long) p99Nanos);
    }

    /**
     * THROUGHPUT: 每秒请求数
     *
     * <p>metricName 对应 Counter 注册名，计算窗口内的平均每秒请求数。</p>
     */
    private double calculateThroughput(SlaTarget target) {
        double totalCount = getCounterValue(target.getMetricName());
        double windowSeconds = target.getWindowMinutes() * 60.0;

        if (windowSeconds == 0) {
            return 0.0;
        }

        return totalCount / windowSeconds;
    }

    /**
     * ERROR_RATE: (错误数 / 总数) * 100
     *
     * <p>指标命名约定：metricName 为基础指标名，
     * 失败计数器为 metricName + ".failed"，总计数器为 metricName + ".total"。
     * 若 metricName + ".total" 不存在，则用成功 + 失败之和作为总数。</p>
     */
    private double calculateErrorRate(SlaTarget target) {
        String errorMetric = target.getMetricName() + ".failed";
        double errorCount = getCounterValue(errorMetric);

        // 尝试获取 total 计数器
        String totalMetric = target.getMetricName() + ".total";
        double totalCount = getCounterValue(totalMetric);

        // 若 total 计数器不存在，尝试用 confirmed + failed 计算
        if (totalCount == 0) {
            double successCount = getCounterValue(target.getMetricName() + ".confirmed");
            totalCount = successCount + errorCount;
        }

        if (totalCount == 0) {
            log.debug("ERROR_RATE 计算: 无请求数据，返回 0.0");
            return 0.0;
        }

        return (errorCount / totalCount) * 100.0;
    }

    /**
     * 从 MeterRegistry 获取 Counter 的累计值。
     */
    private double getCounterValue(String metricName) {
        return meterRegistry.find(metricName).counter() != null
                ? meterRegistry.find(metricName).counter().count()
                : 0.0;
    }

    /**
     * 判断测量值是否达标。
     *
     * <p>不同目标类型的达标逻辑：
     * <ul>
     *   <li>AVAILABILITY: measuredValue >= targetValue（可用率不低于目标）</li>
     *   <li>LATENCY: measuredValue <= targetValue（延迟不高于目标）</li>
     *   <li>THROUGHPUT: measuredValue >= targetValue（吞吐量不低于目标）</li>
     *   <li>ERROR_RATE: measuredValue <= targetValue（错误率不高于目标）</li>
     * </ul></p>
     */
    private boolean evaluateMet(SlaTarget target, double measuredValue) {
        return switch (target.getTargetType()) {
            case AVAILABILITY, THROUGHPUT -> measuredValue >= target.getTargetValue();
            case LATENCY, ERROR_RATE -> measuredValue <= target.getTargetValue();
        };
    }
}
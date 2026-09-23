package org.nexus.gateway.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志采样过滤器（任务 #28）。
 *
 * <p>对高频日志进行采样输出，减少日志量。采样策略：</p>
 * <ul>
 *   <li>ERROR/WARN 级别：始终输出，不采样</li>
 *   <li>INFO/DEBUG/TRACE 级别：按配置采样率输出（如 10% 表示每10条只输出1条）</li>
 *   <li>仅对配置的 logger 名称前缀列表中的 logger 进行采样</li>
 * </ul>
 *
 * <p>采样算法：使用计数器取模方式，确保均匀采样。
 * 例如采样率 0.1（10%），每 10 条日志中第 1 条通过，其余 9 条被过滤。</p>
 *
 * <p>配置示例（application.yml）：</p>
 * <pre>
 * logging:
 *   sampling:
 *     enabled: true
 *     rate: 0.1
 *     loggers:
 *       - "org.springframework.web.filter"
 *       - "org.nexus.gateway.interceptor"
 * </pre>
 */
public class LogSamplingFilter extends Filter<ILoggingEvent> {

    /** 采样率（0.0 ~ 1.0） */
    private double samplingRate = 0.1;

    /** 需要采样的 logger 名称前缀列表 */
    private List<String> samplerLoggers = List.of();

    /** 采样计数器，用于取模判定 */
    private final AtomicLong counter = new AtomicLong(0);

    /**
     * 计算采样间隔（取模基数）。
     * rate=0.1 → 间隔=10；rate=0.01 → 间隔=100；rate=1.0 → 间隔=1（全部通过）。
     */
    private int getSamplingInterval() {
        if (samplingRate >= 1.0) {
            return 1;
        }
        if (samplingRate <= 0.0) {
            return Integer.MAX_VALUE; // 全部拒绝
        }
        return (int) Math.round(1.0 / samplingRate);
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!isStarted()) {
            return FilterReply.NEUTRAL;
        }

        // ERROR/WARN 始终输出，不采样
        Level level = event.getLevel();
        if (level.toInt() >= Level.WARN_INT) {
            return FilterReply.NEUTRAL;
        }

        // 检查当前 logger 是否在采样范围内
        String loggerName = event.getLoggerName();
        if (!isSamplerLogger(loggerName)) {
            return FilterReply.NEUTRAL;
        }

        // 采样判定：计数器取模
        int interval = getSamplingInterval();
        if (interval <= 1) {
            return FilterReply.NEUTRAL; // 采样率 100%，全部通过
        }

        long currentCount = counter.getAndIncrement();
        if (currentCount % interval == 0) {
            return FilterReply.NEUTRAL; // 通过
        }

        return FilterReply.DENY; // 被采样掉
    }

    /**
     * 判断 logger 是否在采样范围内。
     * 使用前缀匹配：loggerName 以 samplerLoggers 中任一项开头则匹配。
     */
    private boolean isSamplerLogger(String loggerName) {
        if (loggerName == null) {
            return false;
        }
        for (String prefix : samplerLoggers) {
            if (loggerName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 设置采样率。
     *
     * @param samplingRate 采样率（0.0 ~ 1.0）
     */
    public void setSamplingRate(double samplingRate) {
        this.samplingRate = samplingRate;
    }

    /**
     * 获取采样率。
     */
    public double getSamplingRate() {
        return samplingRate;
    }

    /**
     * 设置需要采样的 logger 名称前缀列表。
     */
    public void setSamplerLoggers(List<String> samplerLoggers) {
        this.samplerLoggers = samplerLoggers;
    }

    /**
     * 获取需要采样的 logger 名称前缀列表。
     */
    public List<String> getSamplerLoggers() {
        return samplerLoggers;
    }
}
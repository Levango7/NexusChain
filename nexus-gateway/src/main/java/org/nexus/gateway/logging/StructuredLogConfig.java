package org.nexus.gateway.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 结构化日志配置（任务 #28）。
 *
 * <p>通过编程式方式在 Logback 启动后注入 {@link SensitiveDataFilter} 和
 * {@link LogSamplingFilter} 到所有已注册的 Appender 中，实现：</p>
 * <ul>
 *   <li>敏感数据脱敏：检测日志消息中的敏感字段并替换为掩码（通过 {@link MaskingLayout}）</li>
 *   <li>日志采样：对高频 DEBUG 日志按采样率过滤，减少日志量</li>
 * </ul>
 *
 * <p>JSON 格式输出由 {@code logback-spring.xml} 中的 LogstashEncoder 配置实现，
 * 本类负责 Filter 的动态注入和脱敏 Layout 的编程式替换
 *（XML 中无法直接使用 Spring 管理的 Filter Bean 和自定义 Layout）。</p>
 *
 * <p>Profile 切换：dev/sandbox 使用人类可读格式（logback-spring.xml 中配置），
 * prod 使用 JSON 格式输出（logback-spring.xml 中配置），由 Spring Profile 机制自动选择。</p>
 */
@Configuration
@EnableConfigurationProperties(StructuredLogProperties.class)
public class StructuredLogConfig {

    private final StructuredLogProperties properties;

    public StructuredLogConfig(StructuredLogProperties properties) {
        this.properties = properties;
    }

    /**
     * 在 Logback 初始化完成后，注入 Filter 和脱敏 Layout。
     */
    @PostConstruct
    public void injectFiltersAndLayouts() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        SensitiveDataFilter sensitiveFilter = null;
        if (properties.getSensitive().isEnabled()) {
            sensitiveFilter = new SensitiveDataFilter();
            sensitiveFilter.setContext(loggerContext);
            sensitiveFilter.setFields(properties.getSensitive().getFields());
            sensitiveFilter.setMaskPattern(properties.getSensitive().getMaskPattern());
            sensitiveFilter.start();
        }

        LogSamplingFilter samplingFilter = null;
        if (properties.getSampling().isEnabled()) {
            samplingFilter = new LogSamplingFilter();
            samplingFilter.setContext(loggerContext);
            samplingFilter.setSamplingRate(properties.getSampling().getRate());
            samplingFilter.setSamplerLoggers(properties.getSampling().getLogger());
            samplingFilter.start();
        }

        // 注入到 root logger 的所有 appender
        ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(
                org.slf4j.Logger.ROOT_LOGGER_NAME);
        Iterator<Appender<ILoggingEvent>> rootAppenders = rootLogger.iteratorForAppenders();
        while (rootAppenders.hasNext()) {
            Appender<ILoggingEvent> appender = rootAppenders.next();
            injectIntoAppender(appender, sensitiveFilter, samplingFilter, loggerContext);
        }

        // 也注入到所有命名 logger 的 appender（如 AUDIT logger）
        for (ch.qos.logback.classic.Logger logger : loggerContext.getLoggerList()) {
            if (logger == rootLogger) {
                continue;
            }
            Iterator<Appender<ILoggingEvent>> namedAppenders = logger.iteratorForAppenders();
            while (namedAppenders.hasNext()) {
                Appender<ILoggingEvent> appender = namedAppenders.next();
                // 为每个 appender 创建独立的 filter 实例，避免状态共享
                SensitiveDataFilter independentSensitiveFilter = sensitiveFilter != null
                        ? cloneSensitiveFilter(sensitiveFilter, loggerContext) : null;
                LogSamplingFilter independentSamplingFilter = samplingFilter != null
                        ? cloneSamplingFilter(samplingFilter, loggerContext) : null;
                injectIntoAppender(appender, independentSensitiveFilter, independentSamplingFilter, loggerContext);
            }
        }
    }

    /**
     * 为单个 appender 注入 Filter 和脱敏 Layout。
     */
    @SuppressWarnings("unchecked")
    private void injectIntoAppender(Appender<ILoggingEvent> appender,
                                     SensitiveDataFilter sensitiveFilter,
                                     LogSamplingFilter samplingFilter,
                                     LoggerContext loggerContext) {
        // 注入 Filter
        if (samplingFilter != null) {
            appender.addFilter(samplingFilter);
        }
        if (sensitiveFilter != null) {
            appender.addFilter(sensitiveFilter);
        }

        // 注入脱敏 Layout（替换 encoder 的 layout）
        if (sensitiveFilter != null) {
            Encoder<ILoggingEvent> encoder = appender instanceof ch.qos.logback.core.OutputStreamAppender
                    ? ((ch.qos.logback.core.OutputStreamAppender<ILoggingEvent>) appender).getEncoder()
                    : null;
            if (encoder != null) {
                injectMaskingLayout(encoder, sensitiveFilter, loggerContext);
            }
        }
    }

    /**
     * 为 encoder 注入脱敏 Layout。
     *
     * <p>对于 {@link LayoutWrappingEncoder}，直接替换其 layout 为 {@link MaskingLayout}。
     * 对于 {@link LogstashEncoder}，通过反射获取其内部 layout 并替换为 {@link MaskingLayout}。</p>
     */
    @SuppressWarnings("unchecked")
    private void injectMaskingLayout(Encoder<ILoggingEvent> encoder,
                                      SensitiveDataFilter sensitiveFilter,
                                      LoggerContext loggerContext) {
        if (encoder instanceof LayoutWrappingEncoder) {
            LayoutWrappingEncoder<ILoggingEvent> lwe = (LayoutWrappingEncoder<ILoggingEvent>) encoder;
            Layout<ILoggingEvent> originalLayout = lwe.getLayout();
            if (originalLayout != null) {
                MaskingLayout maskingLayout = new MaskingLayout(originalLayout, sensitiveFilter);
                maskingLayout.setContext(loggerContext);
                maskingLayout.start();
                lwe.setLayout(maskingLayout);
            }
        } else if (encoder instanceof LogstashEncoder) {
            // LogstashEncoder 内部使用 LogstashLayout，通过反射获取并替换
            try {
                Field layoutField = encoder.getClass().getSuperclass().getDeclaredField("layout");
                layoutField.setAccessible(true);
                Layout<ILoggingEvent> originalLayout = (Layout<ILoggingEvent>) layoutField.get(encoder);
                if (originalLayout != null) {
                    MaskingLayout maskingLayout = new MaskingLayout(originalLayout, sensitiveFilter);
                    maskingLayout.setContext(loggerContext);
                    maskingLayout.start();
                    layoutField.set(encoder, maskingLayout);
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // LogstashEncoder 内部 layout 字段名可能因版本不同而变化，忽略反射失败
            }
        }
    }

    /**
     * 创建独立的 SensitiveDataFilter 实例（Logback Filter 不支持跨 appender 共享状态）。
     */
    private SensitiveDataFilter cloneSensitiveFilter(SensitiveDataFilter original, LoggerContext context) {
        SensitiveDataFilter clone = new SensitiveDataFilter();
        clone.setContext(context);
        clone.setFields(original.getFields());
        clone.setMaskPattern(original.getMaskPattern());
        clone.start();
        return clone;
    }

    /**
     * 创建独立的 LogSamplingFilter 实例。
     */
    private LogSamplingFilter cloneSamplingFilter(LogSamplingFilter original, LoggerContext context) {
        LogSamplingFilter clone = new LogSamplingFilter();
        clone.setContext(context);
        clone.setSamplingRate(original.getSamplingRate());
        clone.setSamplerLoggers(original.getSamplerLoggers());
        clone.start();
        return clone;
    }
}

package org.nexus.gateway.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.LayoutBase;

/**
 * 脱敏 Layout 包装器（任务 #28）。
 *
 * <p>包装原始 {@link Layout}，在 {@link #doLayout(ILoggingEvent)} 方法中
 * 先调用原始 layout 格式化日志，然后通过 {@link SensitiveDataFilter#maskSensitiveData(String)}
 * 对格式化后的文本进行敏感数据脱敏处理。</p>
 *
 * <p>使用方式：在 {@link StructuredLogConfig} 中编程式替换 appender encoder 的 layout
 * 为本类实例，实现日志输出前的自动脱敏。</p>
 */
public class MaskingLayout extends LayoutBase<ILoggingEvent> {

    private final Layout<ILoggingEvent> delegate;
    private final SensitiveDataFilter sensitiveDataFilter;

    /**
     * 构造脱敏 Layout 包装器。
     *
     * @param delegate            原始 layout（如 PatternLayout / LogstashLayout）
     * @param sensitiveDataFilter 脱敏过滤器（提供 maskSensitiveData 方法）
     */
    public MaskingLayout(Layout<ILoggingEvent> delegate, SensitiveDataFilter sensitiveDataFilter) {
        this.delegate = delegate;
        this.sensitiveDataFilter = sensitiveDataFilter;
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        String formatted = delegate.doLayout(event);
        if (formatted == null || formatted.isEmpty()) {
            return formatted;
        }
        return sensitiveDataFilter.maskSensitiveData(formatted);
    }

    @Override
    public String getContentType() {
        return delegate.getContentType();
    }

    /**
     * 获取被包装的原始 layout。
     */
    public Layout<ILoggingEvent> getDelegate() {
        return delegate;
    }
}
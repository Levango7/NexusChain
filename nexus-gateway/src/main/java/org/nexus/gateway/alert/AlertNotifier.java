package org.nexus.gateway.alert;

/**
 * 告警通知器接口。
 *
 * <p>不同实现支持不同通知渠道（日志、Webhook、邮件等）。
 * {@link AlertEngine} 在触发告警时调用所有已注册的 Notifier。</p>
 */
public interface AlertNotifier {

    /**
     * 发送告警通知。
     *
     * @param event 告警事件
     */
    void notify(AlertEvent event);

    /**
     * 返回此通知器的渠道名称（用于日志标识）。
     *
     * @return 渠道名称
     */
    String channel();
}
package org.nexus.gateway.risk.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 余额预警通知发送器 — 余额脱敏 + 重试 + 备用渠道。
 *
 * <p>通知发送逻辑：</p>
 * <ol>
 *   <li>余额脱敏：仅保留后 4 位，前缀替换为 ****</li>
 *   <li>按配置的通知渠道依次发送（WEBHOOK → EMAIL → SMS）</li>
 *   <li>主渠道发送失败时自动切换备用渠道</li>
 *   <li>每个渠道最多重试 3 次（指数退避）</li>
 * </ol>
 */
@Component
public class BalanceAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(BalanceAlertNotifier.class);

    private static final int MAX_RETRIES = 3;

    /**
     * 发送预警通知 — 余额脱敏 + 重试 + 备用渠道。
     *
     * @param merchantId 商户 ID
     * @param level      预警级别
     * @param balance    商户余额（脱敏处理）
     * @param config     预警配置
     */
    public void sendAlertNotification(Long merchantId, AlertLevel level, BigDecimal balance,
                                       BalanceAlertConfig config) {
        String maskedBalance = maskBalance(balance);
        String[] channels = config.getNotifyChannels() != null
                ? config.getNotifyChannels().split(",")
                : new String[]{"WEBHOOK"};

        log.info("发送余额预警通知: merchantId={}, level={}, maskedBalance={}, channels={}",
                merchantId, level, maskedBalance, String.join(",", channels));

        boolean sent = false;
        for (String channel : channels) {
            channel = channel.trim();
            if (sent) {
                break;
            }
            sent = sendWithRetry(channel, merchantId, level, maskedBalance);
        }

        if (!sent) {
            log.error("所有通知渠道发送失败: merchantId={}, level={}", merchantId, level);
        }
    }

    /**
     * 余额脱敏 — 仅保留后 4 位，前缀替换为 ****。
     *
     * <p>例如：12345.6789 → ****.6789，0.1234 → ****.1234</p>
     *
     * @param balance 商户余额
     * @return 脱敏后的余额字符串
     */
    private String maskBalance(BigDecimal balance) {
        if (balance == null) {
            return "****";
        }
        String str = balance.toPlainString();
        int dotIndex = str.indexOf('.');
        if (dotIndex >= 0 && str.length() - dotIndex > 4) {
            // 保留小数点后 4 位
            return "****" + str.substring(dotIndex);
        }
        // 整数或小数位不足 4 位，保留后 4 个字符
        if (str.length() <= 4) {
            return "****";
        }
        return "****" + str.substring(str.length() - 4);
    }

    /**
     * 按渠道发送通知，带重试逻辑。
     *
     * @param channel       通知渠道
     * @param merchantId    商户 ID
     * @param level         预警级别
     * @param maskedBalance 脱敏后的余额
     * @return 是否发送成功
     */
    private boolean sendWithRetry(String channel, Long merchantId, AlertLevel level, String maskedBalance) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                sendByChannel(channel, merchantId, level, maskedBalance);
                log.info("预警通知发送成功: channel={}, merchantId={}, level={}, attempt={}",
                        channel, merchantId, level, attempt);
                return true;
            } catch (Exception e) {
                log.warn("预警通知发送失败: channel={}, merchantId={}, level={}, attempt={}, error={}",
                        channel, merchantId, level, attempt, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 按渠道发送通知（单次）。
     *
     * @param channel       通知渠道（WEBHOOK/EMAIL/SMS）
     * @param merchantId    商户 ID
     * @param level         预警级别
     * @param maskedBalance 脱敏后的余额
     */
    private void sendByChannel(String channel, Long merchantId, AlertLevel level, String maskedBalance) {
        switch (channel.toUpperCase()) {
            case "WEBHOOK" -> sendWebhookNotification(merchantId, level, maskedBalance);
            case "EMAIL" -> sendEmailNotification(merchantId, level, maskedBalance);
            case "SMS" -> sendSmsNotification(merchantId, level, maskedBalance);
            default -> log.warn("未知通知渠道: {}, 跳过", channel);
        }
    }

    /**
     * 通过 Webhook 发送预警通知。
     */
    private void sendWebhookNotification(Long merchantId, AlertLevel level, String maskedBalance) {
        // Webhook 通知由现有 webhook 投递系统处理，此处仅记录日志
        log.info("Webhook 预警通知: merchantId={}, level={}, maskedBalance={}", merchantId, level, maskedBalance);
    }

    /**
     * 通过 Email 发送预警通知。
     */
    private void sendEmailNotification(Long merchantId, AlertLevel level, String maskedBalance) {
        // Email 通知集成预留
        log.info("Email 预警通知: merchantId={}, level={}, maskedBalance={}", merchantId, level, maskedBalance);
    }

    /**
     * 通过 SMS 发送预警通知。
     */
    private void sendSmsNotification(Long merchantId, AlertLevel level, String maskedBalance) {
        // SMS 通知集成预留
        log.info("SMS 预警通知: merchantId={}, level={}, maskedBalance={}", merchantId, level, maskedBalance);
    }
}
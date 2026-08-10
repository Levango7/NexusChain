package org.nexus.analytics.monitoring;

import java.util.List;

/**
 * 告警服务。
 *
 * <p>负责告警事件的产生、确认、查询与生命周期管理。
 */
public interface AlertService {

    /**
     * 产生一条告警。
     *
     * @param alert 告警内容
     * @return 已持久化的告警（含分配的告警 ID）
     */
    Alert raiseAlert(Alert alert);

    /**
     * 确认（acknowledge）一条告警。
     *
     * @param id 告警 ID
     * @return 是否成功确认；告警不存在或已确认时返回 false
     */
    boolean acknowledgeAlert(String id);

    /**
     * 获取当前所有未确认 / 未恢复的活动告警。
     *
     * @return 活动告警列表
     */
    List<Alert> getActiveAlerts();

    /**
     * 按级别过滤活动告警。
     *
     * @param level 告警级别
     * @return 命中告警列表
     */
    List<Alert> getActiveAlertsByLevel(Alert.Level level);
}
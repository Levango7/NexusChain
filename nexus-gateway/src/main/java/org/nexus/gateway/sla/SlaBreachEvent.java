package org.nexus.gateway.sla;

import org.springframework.context.ApplicationEvent;

/**
 * SLA 违约事件。
 *
 * <p>当 SLA 监控检测到某项目标未达标时，通过 Spring ApplicationEvent 机制
 * 发布此事件，供下游监听器处理告警通知、自动扩容等响应动作。</p>
 */
public class SlaBreachEvent extends ApplicationEvent {

    private final SlaTarget slaTarget;
    private final SlaMeasurement slaMeasurement;

    /**
     * 构造 SLA 违约事件。
     *
     * @param source        事件源（通常为 SlaMonitorService）
     * @param slaTarget     未达标的 SLA 目标
     * @param slaMeasurement 触发违约的测量记录
     */
    public SlaBreachEvent(Object source, SlaTarget slaTarget, SlaMeasurement slaMeasurement) {
        super(source);
        this.slaTarget = slaTarget;
        this.slaMeasurement = slaMeasurement;
    }

    public SlaTarget getSlaTarget() {
        return slaTarget;
    }

    public SlaMeasurement getSlaMeasurement() {
        return slaMeasurement;
    }
}
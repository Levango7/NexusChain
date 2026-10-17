package org.nexus.gateway.security.audit;

/**
 * 安全审计事件的落库接缝，由 {@code audit} 切片实现（依赖倒置）。
 *
 * <p>为什么要有这个接口而不是让 {@link SecurityAuditService} 直接调
 * {@code org.nexus.gateway.audit.AuditLogService}：仓内已存在
 * {@code audit → config → security} 这条链（{@code config.SecurityConfig} 依赖
 * {@code security.JwtAuthenticationFilter}，而 {@code audit} 侧有一处测试依赖 {@code config}），
 * 再补一条 {@code security → audit} 就直接把 ArchUnit 的
 * "slices should be free of cycles" 关成环——实测红过一次。方向反过来
 * （{@code audit} 实现本接口）既成不了环，也让"事件长什么样"归 security、
 * "怎么存"归 audit。</p>
 */
public interface SecurityAuditSink {

    /**
     * 把一个安全审计事件持久化到可查询的存储。实现方须自行保证列宽与脱敏约束。
     *
     * @param event 已完成脱敏的安全审计事件
     */
    void persist(AuditEvent event);
}

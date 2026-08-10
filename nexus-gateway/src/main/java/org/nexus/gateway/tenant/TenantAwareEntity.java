package org.nexus.gateway.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * 租户隔离实体基类（P4-T6 多租户改造）。
 *
 * <p>所有需要租户隔离的业务实体（PaymentOrder、Refund、Subscription 等）继承本类，
 * 通过 {@code tenant_id} 列实现行级数据隔离。{@link TenantContext#getCurrentTenantId()}
 * 在 Service 层填充该字段，Repository 查询时按此字段过滤。</p>
 *
 * <p>使用 {@link MappedSuperclass} 让继承类的 JPA 映射包含本类的 {@code tenantId} 列，
 * 避免每个实体重复声明。</p>
 */
@MappedSuperclass
public abstract class TenantAwareEntity {

    /** 业务租户 ID（数据隔离键，对应 tenants.tenant_id）。 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
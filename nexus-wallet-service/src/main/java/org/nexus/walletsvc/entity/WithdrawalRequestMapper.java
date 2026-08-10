package org.nexus.walletsvc.entity;

import org.nexus.sdk.wallet.WithdrawalRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link WithdrawalRequestEntity}（持久化 Entity）与 {@link WithdrawalRequest}
 * （SDK 跨服务传输 DTO）之间的双向转换工具类。
 *
 * <p>设计文档 §4.2.1：审批人列表（{@code withdrawal_approvers} 一对多）不通过 JPA
 * {@code @OneToMany} 自动加载，由 Service 层显式查询后通过本 Mapper 注入 / 提取，
 * 避免 N+1 查询与级联复杂性。</p>
 *
 * <p>本类为无状态工具类，方法均为静态方法，无需 Spring 容器托管。</p>
 */
public final class WithdrawalRequestMapper {

    private WithdrawalRequestMapper() {
        // 工具类，禁止实例化
    }

    /**
     * Entity → DTO 转换，注入审批人列表。
     *
     * @param entity   提现请求 Entity（不含 approvers）
     * @param approvers 关联的审批人 Entity 列表（可为空）
     * @return SDK DTO，含 approvers ID 列表
     */
    public static WithdrawalRequest toDto(WithdrawalRequestEntity entity,
                                          List<WithdrawalApproverEntity> approvers) {
        WithdrawalRequest dto = new WithdrawalRequest();
        dto.setRequestId(entity.getRequestId());
        dto.setToAddress(entity.getToAddress());
        dto.setAmount(entity.getAmount());
        dto.setCurrency(entity.getCurrency());
        dto.setStatus(entity.getStatus());
        dto.setRequiredApprovers(entity.getRequiredApprovers());
        dto.setApprovedCount(entity.getApprovedCount());
        dto.setChainTxHash(entity.getChainTxHash());
        dto.setRejectionReason(entity.getRejectionReason());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setExecutedAt(entity.getExecutedAt());

        if (approvers == null || approvers.isEmpty()) {
            dto.setApprovers(new ArrayList<>());
        } else {
            dto.setApprovers(approvers.stream()
                    .map(WithdrawalApproverEntity::getApproverId)
                    .collect(Collectors.toList()));
        }
        return dto;
    }

    /**
     * Entity → DTO 转换，approvers 置空。
     *
     * <p>适用于仅需请求主体信息、不需要审批人列表的场景（如状态查询）。</p>
     */
    public static WithdrawalRequest toDto(WithdrawalRequestEntity entity) {
        return toDto(entity, null);
    }

    /**
     * DTO → Entity 转换，不包含 approvers（approvers 由 Service 层单独持久化到
     * {@code withdrawal_approvers} 表）。
     *
     * <p>注意：本方法不复制 {@code id} / {@code version}（由 JPA 自动管理），
     * 也不复制 {@code createdAt} / {@code updatedAt}（由 Entity 的 {@code @PrePersist}
     * / {@code @PreUpdate} 自动维护）。调用方在更新场景应使用 {@link #mergeEntity}
     * 将 DTO 字段合并到已加载的 Entity。</p>
     *
     * @param dto SDK DTO
     * @return 新建的 Entity（未持久化状态）
     */
    public static WithdrawalRequestEntity toEntity(WithdrawalRequest dto) {
        WithdrawalRequestEntity entity = new WithdrawalRequestEntity();
        entity.setRequestId(dto.getRequestId());
        entity.setToAddress(dto.getToAddress());
        entity.setAmount(dto.getAmount());
        entity.setCurrency(dto.getCurrency());
        entity.setStatus(dto.getStatus());
        entity.setRequiredApprovers(dto.getRequiredApprovers());
        entity.setApprovedCount(dto.getApprovedCount());
        entity.setChainTxHash(dto.getChainTxHash());
        entity.setRejectionReason(dto.getRejectionReason());
        entity.setExecutedAt(dto.getExecutedAt());
        // createdAt / updatedAt 由 @PrePersist 自动维护
        return entity;
    }

    /**
     * 将 DTO 字段合并到已存在的 Entity（更新场景）。
     *
     * <p>仅合并业务字段，不触碰 {@code id} / {@code version} / {@code createdAt}
     * （由 JPA 管理），{@code updatedAt} 由 Entity 的 {@code @PreUpdate} 自动维护。</p>
     *
     * @param dto    源 DTO
     * @param entity 目标 Entity（已加载自数据库）
     */
    public static void mergeEntity(WithdrawalRequest dto, WithdrawalRequestEntity entity) {
        entity.setToAddress(dto.getToAddress());
        entity.setAmount(dto.getAmount());
        entity.setCurrency(dto.getCurrency());
        entity.setStatus(dto.getStatus());
        entity.setRequiredApprovers(dto.getRequiredApprovers());
        entity.setApprovedCount(dto.getApprovedCount());
        entity.setChainTxHash(dto.getChainTxHash());
        entity.setRejectionReason(dto.getRejectionReason());
        entity.setExecutedAt(dto.getExecutedAt());
    }
}
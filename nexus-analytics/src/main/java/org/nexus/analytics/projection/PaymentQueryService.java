package org.nexus.analytics.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 支付查询服务（CQRS Query 侧）。
 *
 * <p>提供基于读模型 {@link PaymentReadModel} 的查询能力，
 * 完全独立于命令侧（nexus-gateway）的事务与聚合根。
 *
 * <p>查询语义：
 * <ul>
 *   <li>按聚合根 ID 查单条</li>
 *   <li>按商户 ID 列表查询</li>
 *   <li>按状态过滤</li>
 *   <li>按时间区间过滤</li>
 *   <li>统计：总数、金额合计、按状态分组计数</li>
 * </ul>
 *
 * <p>所有查询仅访问读模型存储，不触发事件溯源重放，
 * 满足 CQRS"读写分离"原则。读模型由 {@link PaymentProjection} 异步投影维护，
 * 最终一致（典型延迟 < 1s）。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
@Service
public class PaymentQueryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentQueryService.class);

    private final PaymentProjection paymentProjection;

    public PaymentQueryService(PaymentProjection paymentProjection) {
        this.paymentProjection = paymentProjection;
    }

    /**
     * 按聚合根 ID 查询单条读模型。
     *
     * @param aggregateId 聚合根 ID
     * @return 读模型（不存在返回 null）
     */
    public PaymentReadModel findById(String aggregateId) {
        if (aggregateId == null || aggregateId.isBlank()) {
            return null;
        }
        return paymentProjection.getReadModelStore().get(aggregateId);
    }

    /**
     * 按商户 ID 查询全部支付（按版本号升序）。
     *
     * @param merchantId 商户 ID
     * @return 读模型列表
     */
    public List<PaymentReadModel> findByMerchantId(Long merchantId) {
        if (merchantId == null) {
            return List.of();
        }
        return paymentProjection.getReadModelStore().values().stream()
                .filter(rm -> merchantId.equals(rm.getMerchantId()))
                .sorted(Comparator.comparingLong(PaymentReadModel::getVersion))
                .collect(Collectors.toList());
    }

    /**
     * 按状态过滤查询。
     *
     * @param state 状态枚举
     * @return 读模型列表
     */
    public List<PaymentReadModel> findByState(PaymentReadModel.State state) {
        if (state == null) {
            return List.of();
        }
        return paymentProjection.getReadModelStore().values().stream()
                .filter(rm -> state == rm.getState())
                .collect(Collectors.toList());
    }

    /**
     * 按时间区间查询（基于读模型 updatedAt）。
     *
     * @param from 起始时间（含，UTC）
     * @param to   结束时间（不含，UTC）
     * @return 读模型列表
     */
    public List<PaymentReadModel> findByTimeRange(Instant from, Instant to) {
        return paymentProjection.getReadModelStore().values().stream()
                .filter(rm -> rm.getUpdatedAt() != null)
                .filter(rm -> from == null || !rm.getUpdatedAt().isBefore(from))
                .filter(rm -> to == null || rm.getUpdatedAt().isBefore(to))
                .collect(Collectors.toList());
    }

    /**
     * 分页查询全部支付。
     *
     * @param page 页码（0-based）
     * @param size 每页大小
     * @return 当前页的读模型列表
     */
    public List<PaymentReadModel> findAll(int page, int size) {
        if (page < 0 || size <= 0) {
            return List.of();
        }
        List<PaymentReadModel> all = new ArrayList<>(paymentProjection.getReadModelStore().values());
        all.sort(Comparator.comparing(PaymentReadModel::getAggregateId));
        int fromIndex = page * size;
        if (fromIndex >= all.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + size, all.size());
        return all.subList(fromIndex, toIndex);
    }

    /**
     * 统计全部支付数量。
     */
    public long countAll() {
        return paymentProjection.getReadModelStore().size();
    }

    /**
     * 按商户统计支付数量。
     */
    public long countByMerchant(Long merchantId) {
        if (merchantId == null) {
            return 0L;
        }
        return paymentProjection.getReadModelStore().values().stream()
                .filter(rm -> merchantId.equals(rm.getMerchantId()))
                .count();
    }

    /**
     * 统计成功支付的总金额（按商户）。
     *
     * @param merchantId 商户 ID
     * @return 成功支付金额合计（无则返回 0）
     */
    public BigDecimal sumSucceededAmountByMerchant(Long merchantId) {
        if (merchantId == null) {
            return BigDecimal.ZERO;
        }
        return paymentProjection.getReadModelStore().values().stream()
                .filter(rm -> merchantId.equals(rm.getMerchantId()))
                .filter(rm -> rm.getState() == PaymentReadModel.State.SUCCEEDED)
                .map(rm -> rm.getSettledAmount() != null ? rm.getSettledAmount() : rm.getAmount())
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 按状态分组统计数量。
     *
     * @return 状态 → 数量 映射
     */
    public Map<PaymentReadModel.State, Long> countByState() {
        return paymentProjection.getReadModelStore().values().stream()
                .collect(Collectors.groupingBy(
                        rm -> rm.getState() != null ? rm.getState() : PaymentReadModel.State.CREATED,
                        Collectors.counting()));
    }

    /**
     * 查询读模型当前规模（运维用）。
     */
    public int readModelSize() {
        return paymentProjection.getReadModelStore().size();
    }
}
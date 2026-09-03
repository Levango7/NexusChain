package org.nexus.settlement.reconciliation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 链上记录数据源（账务核心持久化，双模式）。
 * <p>
 * 生产环境应替换为经 nexus-core RPC / 事件订阅拉取已确认结算交易的实现；
 * 当前实现允许外部通过 {@link #feed} 注入链上记录，用于对账与测试。
 * </p>
 * <p>
 * 持久化设计：不新增第二个 Bean（避免 {@code ChainRecordSource} 按类型注入歧义），
 * 在本 Bean 上以 {@code @Autowired(required=false)} 可选注入
 * {@link SettlementRecordRepository}（source=CHAIN）：
 * <ul>
 *   <li><b>DB 模式</b>：fetch 查表、feed 幂等落库、clear 按源清空，重启不丢</li>
 *   <li><b>内存模式</b>：保留原 CopyOnWriteArrayList 语义，纯单测零破坏</li>
 * </ul>
 * </p>
 * <p>
 * 实现 {@link ChainRecordFeedable}：DefaultClearingEngine 结算成功后
 * 通过该端口回填链上记录，形成「结算 → 链上凭证 → 对账匹配」的闭环。
 * </p>
 */
@Component
public class InMemoryChainRecordSource implements ChainRecordSource, ChainRecordFeedable {

    /** 内存模式记录（DB 模式下不使用） */
    private final List<SettlementRecord> records = new CopyOnWriteArrayList<>();

    /** 对账记录仓储（null 则走内存模式） */
    private final SettlementRecordRepository repository;

    /** 纯内存构造器（既有测试 new InMemoryChainRecordSource() 走此路径） */
    public InMemoryChainRecordSource() {
        this(null);
    }

    /**
     * 持久化构造器。repository 由 Spring 容器提供。
     *
     * @param repository 对账记录仓储（null 时回退内存模式）
     */
    @Autowired
    public InMemoryChainRecordSource(@Autowired(required = false) SettlementRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<SettlementRecord> fetchChainRecords() {
        if (repository != null) {
            return repository.findBySource(SettlementRecord.SOURCE_CHAIN);
        }
        return List.copyOf(records);
    }

    /**
     * 注入链上记录（测试或事件回放用）。
     *
     * @param newRecords 待注入记录
     */
    public void feed(List<SettlementRecord> newRecords) {
        if (newRecords == null) {
            return;
        }
        if (repository != null) {
            for (SettlementRecord record : newRecords) {
                saveIfAbsent(record);
            }
            return;
        }
        records.addAll(newRecords);
    }

    /**
     * 结算引擎回填单笔链上记录（{@link ChainRecordFeedable} 实现）。
     * 以 reference 幂等去重（DB 模式由 {@code UNIQUE(reference, source)} 约束兜底），
     * 重复回填不产生重复记录。
     */
    @Override
    public void feedSettlementRecord(SettlementRecord record) {
        if (record == null || record.getReference() == null) {
            return;
        }
        if (repository != null) {
            saveIfAbsent(record);
            return;
        }
        boolean exists = records.stream()
                .anyMatch(r -> record.getReference().equals(r.getReference()));
        if (!exists) {
            records.add(record);
        }
    }

    /** 清空记录。 */
    public void clear() {
        if (repository != null) {
            repository.deleteBySource(SettlementRecord.SOURCE_CHAIN);
            return;
        }
        records.clear();
    }

    /** DB 模式：按 (reference, CHAIN) 幂等落库 */
    private void saveIfAbsent(SettlementRecord record) {
        if (repository.findByReferenceAndSource(
                record.getReference(), SettlementRecord.SOURCE_CHAIN).isPresent()) {
            return;
        }
        record.setSource(SettlementRecord.SOURCE_CHAIN);
        repository.save(record);
    }
}
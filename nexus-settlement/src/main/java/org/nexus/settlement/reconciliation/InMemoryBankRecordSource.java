package org.nexus.settlement.reconciliation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 银行渠道记录数据源（账务核心持久化，双模式）。
 * <p>
 * 生产环境应替换为解析银行对账文件（CSV/ISO20022）或调用渠道 API 的实现；
 * 当前实现允许外部通过 {@link #feed} 注入银行记录，用于对账与测试。
 * </p>
 * <p>
 * 持久化设计：与 {@link InMemoryChainRecordSource} 同构，在本 Bean 上可选注入
 * {@link SettlementRecordRepository}（source=BANK），不新增第二个 Bean。
 * </p>
 */
@Component
public class InMemoryBankRecordSource implements BankRecordSource {

    /** 内存模式记录（DB 模式下不使用） */
    private final List<SettlementRecord> records = new CopyOnWriteArrayList<>();

    /** 对账记录仓储（null 则走内存模式） */
    private final SettlementRecordRepository repository;

    /** 纯内存构造器（既有测试 new InMemoryBankRecordSource() 走此路径） */
    public InMemoryBankRecordSource() {
        this(null);
    }

    /**
     * 持久化构造器。repository 由 Spring 容器提供。
     *
     * @param repository 对账记录仓储（null 时回退内存模式）
     */
    @Autowired
    public InMemoryBankRecordSource(@Autowired(required = false) SettlementRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<SettlementRecord> fetchBankRecords() {
        if (repository != null) {
            return repository.findBySource(SettlementRecord.SOURCE_BANK);
        }
        return List.copyOf(records);
    }

    /**
     * 注入银行记录（测试或对账文件解析回放用）。
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

    /** 清空记录。 */
    public void clear() {
        if (repository != null) {
            repository.deleteBySource(SettlementRecord.SOURCE_BANK);
            return;
        }
        records.clear();
    }

    /** DB 模式：按 (reference, BANK) 幂等落库 */
    private void saveIfAbsent(SettlementRecord record) {
        if (repository.findByReferenceAndSource(
                record.getReference(), SettlementRecord.SOURCE_BANK).isPresent()) {
            return;
        }
        record.setSource(SettlementRecord.SOURCE_BANK);
        repository.save(record);
    }
}
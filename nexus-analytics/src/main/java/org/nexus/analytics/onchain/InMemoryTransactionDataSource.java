package org.nexus.analytics.onchain;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 基于内存的链上交易数据源实现。
 *
 * <p>生产环境应替换为经 nexus-core RPC / 事件订阅拉取的实现；
 * 当前实现允许外部通过 {@link #feed} 注入交易记录，用于图谱构建、统计与测试。
 */
@Component
public class InMemoryTransactionDataSource implements TransactionDataSource {

    private final List<OnChainTransaction> transactions = new CopyOnWriteArrayList<>();

    @Override
    public List<OnChainTransaction> fetchAll() {
        return List.copyOf(transactions);
    }

    @Override
    public List<OnChainTransaction> fetchBetween(Instant start, Instant end) {
        if (start == null || end == null) {
            return List.of();
        }
        return transactions.stream()
                .filter(tx -> tx.getTimestamp() != null)
                .filter(tx -> !tx.getTimestamp().isBefore(start) && tx.getTimestamp().isBefore(end))
                .collect(Collectors.toList());
    }

    @Override
    public List<OnChainTransaction> fetchByAddress(String address) {
        if (address == null || address.isBlank()) {
            return List.of();
        }
        return transactions.stream()
                .filter(tx -> address.equals(tx.getFromAddress()) || address.equals(tx.getToAddress()))
                .collect(Collectors.toList());
    }

    /**
     * 注入交易记录（测试或事件回放用）。
     *
     * @param newTransactions 待注入交易
     */
    public void feed(List<OnChainTransaction> newTransactions) {
        if (newTransactions != null) {
            transactions.addAll(newTransactions);
        }
    }

    /** 清空交易记录。 */
    public void clear() {
        transactions.clear();
    }
}

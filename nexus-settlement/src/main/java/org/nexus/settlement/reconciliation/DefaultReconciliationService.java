package org.nexus.settlement.reconciliation;

import org.springframework.stereotype.Service;

/**
 * 默认对账服务骨架实现。
 * <p>
 * 当前为空实现占位，所有方法体留待后续业务逻辑填充。
 * </p>
 */
@Service
public class DefaultReconciliationService implements ReconciliationService {

    @Override
    public ReconciliationReport reconcileWithChain() {
        // TODO: 实现与链上数据的对账逻辑（拉取链上流水 → 比对本地清算记录 → 生成报告）
        return new ReconciliationReport();
    }

    @Override
    public ReconciliationReport reconcileWithBank() {
        // TODO: 实现与银行渠道的对账逻辑（拉取银行对账文件 → 比对 → 生成报告）
        return new ReconciliationReport();
    }

    @Override
    public ReconciliationReport reportDiscrepancy(ReconciliationReport report) {
        // TODO: 实现差错明细上报与人工介入触发逻辑
        return report;
    }
}
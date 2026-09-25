package org.nexus.gateway.clearing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 清算结算事件监听器 — 监听 {@link ClearingBatchCompletedEvent} 自动触发清算入账。
 *
 * <p>当清算批次完成时，自动调用 {@link ClearingSettlementService#processClearingBatch}
 * 将清算结果写入商户余额。使用 {@code @Async} 异步执行，联动失败不阻断主流程。</p>
 */
@Component
public class ClearingSettlementListener {

    private static final Logger log = LoggerFactory.getLogger(ClearingSettlementListener.class);

    private final ClearingSettlementService clearingSettlementService;

    public ClearingSettlementListener(ClearingSettlementService clearingSettlementService) {
        this.clearingSettlementService = clearingSettlementService;
    }

    /**
     * 监听清算批次完成事件 — 异步执行清算入账。
     *
     * <p>联动失败不阻断主流程，仅记录日志。</p>
     *
     * @param event 清算批次完成事件
     */
    @Async
    @EventListener
    public void onClearingBatchCompleted(ClearingBatchCompletedEvent event) {
        try {
            log.info("收到清算批次完成事件: batchNo={}, merchantCount={}",
                    event.getBatchNo(), event.getSettlementDetails().size());

            ClearingSettlementService.ClearingBatchResult result =
                    clearingSettlementService.processClearingBatch(
                            event.getBatchNo(), event.getSettlementDetails());

            log.info("清算入账完成: batchNo={}, success={}, fail={}",
                    result.getBatchNo(), result.getSuccessCount(), result.getFailCount());
        } catch (Exception e) {
            log.error("清算入账事件处理失败: batchNo={}, error={}",
                    event.getBatchNo(), e.getMessage(), e);
        }
    }
}
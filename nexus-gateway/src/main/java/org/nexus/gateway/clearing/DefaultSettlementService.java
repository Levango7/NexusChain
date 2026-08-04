package org.nexus.gateway.clearing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Default skeleton implementation of {@link SettlementService}.
 *
 * <p>All methods are stubbed and log a TODO marker. Production wiring should
 * integrate with the {@code nexus-settlement} module for batch computation,
 * fee calculation, and on-chain settlement execution.</p>
 */
@Service
public class DefaultSettlementService implements SettlementService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSettlementService.class);

    @Override
    public SettlementBatch createSettlementBatch(Long merchantId, SettlementPeriod period) {
        // TODO: query captured-but-unsettled transactions for merchantId within the period window
        // TODO: compute gross amount, fee (via FeeSchedule), and net amount
        // TODO: persist a new SettlementBatch in OPEN status and return it
        log.warn("createSettlementBatch not implemented: merchantId={}, period={}", merchantId, period);
        SettlementBatch stub = new SettlementBatch();
        stub.setMerchantId(merchantId);
        stub.setPeriod(period);
        stub.setWindowStart(LocalDateTime.now());
        stub.setWindowEnd(LocalDateTime.now());
        return stub;
    }

    @Override
    public SettlementBatch executeSettlement(Long batchId) {
        // TODO: load batch, transition to EXECUTING, trigger on-chain settlement transfer
        // TODO: on success transition to COMPLETED with chainTxHash; on failure transition to FAILED
        log.warn("executeSettlement not implemented: batchId={}", batchId);
        return null;
    }

    @Override
    public SettlementBatch getSettlementStatus(Long batchId) {
        // TODO: load and return the batch by ID
        log.warn("getSettlementStatus not implemented: batchId={}", batchId);
        return null;
    }

    @Override
    public List<SettlementBatch> generateSettlementReport(Long merchantId, SettlementPeriod period) {
        // TODO: query all batches for merchantId within the period and assemble a report
        log.warn("generateSettlementReport not implemented: merchantId={}, period={}", merchantId, period);
        return new ArrayList<>();
    }
}
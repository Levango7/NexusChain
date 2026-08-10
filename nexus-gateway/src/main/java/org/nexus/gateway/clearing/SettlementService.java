package org.nexus.gateway.clearing;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Settlement service interface for creating and executing settlement batches.
 *
 * <p>A settlement batch aggregates captured transactions for a merchant over a
 * given period, computes fees and net amounts, and triggers an on-chain
 * transfer to the merchant's settlement wallet when executed.</p>
 */
public interface SettlementService {

    /**
     * Create a settlement batch for a merchant covering the given period.
     *
     * <p>This collects all captured-but-unsettled transactions for the merchant
     * within the period window, computes gross/fee/net amounts, and persists
     * the batch in {@link SettlementBatch.BatchStatus#OPEN} status.</p>
     *
     * @param merchantId merchant ID
     * @param period     settlement period
     * @return the created settlement batch
     */
    SettlementBatch createSettlementBatch(Long merchantId, SettlementPeriod period);

    /**
     * Execute a previously created settlement batch.
     *
     * <p>This transitions the batch to {@link SettlementBatch.BatchStatus#EXECUTING},
     * triggers the on-chain settlement transfer, and on success transitions to
     * {@link SettlementBatch.BatchStatus#COMPLETED}.</p>
     *
     * @param batchId settlement batch ID
     * @return the updated settlement batch
     */
    SettlementBatch executeSettlement(Long batchId);

    /**
     * Query the current status of a settlement batch.
     *
     * @param batchId settlement batch ID
     * @return the settlement batch, or {@code null} if not found
     */
    SettlementBatch getSettlementStatus(Long batchId);

    /**
     * Generate a human-readable settlement report for a merchant over a period.
     *
     * @param merchantId merchant ID
     * @param period     settlement period
     * @return list of settlement batches in the report
     */
    List<SettlementBatch> generateSettlementReport(Long merchantId, SettlementPeriod period);
}
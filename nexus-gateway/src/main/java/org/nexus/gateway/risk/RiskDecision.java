package org.nexus.gateway.risk;

/**
 * Risk decision enumeration returned by the risk evaluation pipeline.
 *
 * <ul>
 *   <li>{@link #APPROVED} — risk check passed, operation may proceed.</li>
 *   <li>{@link #REJECTED} — risk check failed, operation must be blocked.</li>
 *   <li>{@link #PENDING_REVIEW} — operation held for manual review by a risk officer.</li>
 *   <li>{@link #FROZEN} — merchant or payer is frozen; all operations are blocked.</li>
 * </ul>
 */
public enum RiskDecision {

    /** Risk check passed, operation may proceed. */
    APPROVED,

    /** Risk check failed, operation must be blocked. */
    REJECTED,

    /** Operation held for manual review by a risk officer. */
    PENDING_REVIEW,

    /** Merchant or payer is frozen; all operations are blocked. */
    FROZEN
}
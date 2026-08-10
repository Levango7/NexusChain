package org.nexus.gateway.clearing;

/**
 * Settlement period enumeration defining when merchant funds are settled.
 *
 * <ul>
 *   <li>{@link #T0} — same-day settlement, funds released immediately after capture.</li>
 *   <li>{@link #T1} — next-business-day settlement.</li>
 *   <li>{@link #WEEKLY} — settlement once per week.</li>
 *   <li>{@link #MONTHLY} — settlement once per calendar month.</li>
 * </ul>
 */
public enum SettlementPeriod {

    /** Same-day settlement, funds released immediately after capture. */
    T0,

    /** Next-business-day settlement. */
    T1,

    /** Weekly settlement. */
    WEEKLY,

    /** Monthly settlement. */
    MONTHLY
}
package org.nexus.gateway.clearing;

/**
 * Settlement period enumeration defining when merchant funds are settled.
 *
 * <ul>
 *   <li>{@link #T0} — same-day settlement, funds released immediately after capture.</li>
 *   <li>{@link #T1} — next-business-day settlement.</li>
 *   <li>{@link #T2} — T+2 settlement, funds released 2 business days after capture.</li>
 *   <li>{@link #T3} — T+3 settlement, funds released 3 business days after capture.</li>
 *   <li>{@link #WEEKLY} — settlement once per week.</li>
 *   <li>{@link #MONTHLY} — settlement once per calendar month.</li>
 *   <li>{@link #CUSTOM} — custom settlement period, used with {@code customDays}.</li>
 * </ul>
 */
public enum SettlementPeriod {

    /** Same-day settlement, funds released immediately after capture. */
    T0,

    /** Next-business-day settlement. */
    T1,

    /** T+2 settlement, funds released 2 business days after capture. */
    T2,

    /** T+3 settlement, funds released 3 business days after capture. */
    T3,

    /** Weekly settlement. */
    WEEKLY,

    /** Monthly settlement. */
    MONTHLY,

    /** Custom settlement period, used with {@code customDays} (1-90 days). */
    CUSTOM
}
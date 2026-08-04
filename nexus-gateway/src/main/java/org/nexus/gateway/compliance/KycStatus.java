package org.nexus.gateway.compliance;

/**
 * KYC (Know Your Customer) status enumeration.
 *
 * <ul>
 *   <li>{@link #NONE} — no KYC collected yet.</li>
 *   <li>{@link #BASIC} — basic identity information collected and verified.</li>
 *   <li>{@link #ENHANCED} — enhanced due diligence completed for high-risk customers.</li>
 *   <li>{@link #VERIFIED} — fully verified, all checks passed.</li>
 *   <li>{@link #REJECTED} — KYC submission rejected.</li>
 * </ul>
 */
public enum KycStatus {

    /** No KYC collected yet. */
    NONE,

    /** Basic identity information collected and verified. */
    BASIC,

    /** Enhanced due diligence completed for high-risk customers. */
    ENHANCED,

    /** Fully verified, all checks passed. */
    VERIFIED,

    /** KYC submission rejected. */
    REJECTED
}
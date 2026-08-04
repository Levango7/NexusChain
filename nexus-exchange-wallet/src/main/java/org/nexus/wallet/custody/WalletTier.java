package org.nexus.wallet.custody;

/**
 * Wallet tier enumeration for the hot/warm/cold custody model.
 *
 * <ul>
 *   <li>{@link #HOT} — online wallet for immediate withdrawals; holds a small fraction of funds.</li>
 *   <li>{@link #WARM} — semi-online wallet; requires limited approval to move funds.</li>
 *   <li>{@link #COLD} — offline/air-gapped wallet holding the bulk of funds; requires full multi-sig approval.</li>
 * </ul>
 */
public enum WalletTier {

    /** Online wallet for immediate withdrawals; holds a small fraction of funds. */
    HOT,

    /** Semi-online wallet; requires limited approval to move funds. */
    WARM,

    /** Offline/air-gapped wallet holding the bulk of funds; requires full multi-sig approval. */
    COLD
}
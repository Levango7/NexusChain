package org.nexus.gateway.currency;

/**
 * Supported currency enumeration covering fiat, crypto, and the native NEXUS token.
 *
 * <ul>
 *   <li>{@link #USD}, {@link #CNY}, {@link #EUR} — fiat currencies.</li>
 *   <li>{@link #BTC}, {@link #ETH} — native crypto assets.</li>
 *   <li>{@link #USDT}, {@link #USDC} — stablecoins.</li>
 *   <li>{@link #NEXUS} — the native NexusChain token.</li>
 * </ul>
 */
public enum Currency {

    /** US Dollar. */
    USD,

    /** Chinese Yuan. */
    CNY,

    /** Euro. */
    EUR,

    /** Bitcoin. */
    BTC,

    /** Ether. */
    ETH,

    /** Tether USD stablecoin. */
    USDT,

    /** USD Coin stablecoin. */
    USDC,

    /** Native NexusChain token. */
    NEXUS
}
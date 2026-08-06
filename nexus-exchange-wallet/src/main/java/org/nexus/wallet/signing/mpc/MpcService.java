package org.nexus.wallet.signing.mpc;

import java.util.List;

/**
 * MPC (Multi-Party Computation) service interface for threshold-signature
 * wallet management and distributed signing.
 *
 * <p>Implementations should integrate a proven MPC protocol such as
 * <a href="https://eprint.iacr.org/2020/540">GG18</a> or
 * <a href="https://eprint.iacr.org/2020/540">GG20</a> for ECDSA threshold
 * signatures. The joint public key is the only on-chain identity; private
 * key shares never exist in a single location.</p>
 */
public interface MpcService {

    /**
     * Create a new MPC wallet shared among the given participants with the
     * given threshold.
     *
     * @param participants list of participant identifiers
     * @param threshold    number of participants required to sign (1 &lt; threshold &lt;= participants.size())
     * @return the created MPC wallet with the joint public key populated
     */
    MpcWallet createMpcWallet(List<String> participants, int threshold);

    /**
     * Initiate and complete a distributed signing round for the given wallet
     * and transaction data.
     *
     * @param walletId wallet ID
     * @param txData   transaction data to sign (raw bytes encoded as hex)
     * @return the combined signature, or {@code null} if the round failed
     */
    String signTransaction(String walletId, String txData);

    /**
     * Rotate the key shares of an existing MPC wallet without changing the
     * joint public key. Used for proactive security refresh.
     *
     * @param walletId wallet ID
     * @return the updated MPC wallet with lastRotatedAt refreshed
     */
    MpcWallet rotateKey(String walletId);
}
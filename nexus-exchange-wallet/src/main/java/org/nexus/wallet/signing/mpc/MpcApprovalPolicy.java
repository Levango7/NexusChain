package org.nexus.wallet.signing.mpc;

import org.nexus.sdk.signing.ApprovalPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * MPC-aware approval policy implementing {@link ApprovalPolicy}.
 *
 * <p>Bridges the existing approval workflow with the MPC threshold-signature
 * flow. The number of required approvers returned by {@link #getRequiredApprovers}
 * is the MPC threshold {@code t} for the wallet governing the given
 * amount/currency tier. A withdrawal is considered signable once {@code t}
 * approvers have approved AND those approvers correspond to online MPC
 * participants; the latter check is exposed via {@link #canSign}.</p>
 *
 * <p>This component coexists with {@code DefaultApprovalPolicy} without
 * replacing it: callers that need MPC semantics inject this bean by name
 * ({@code mpcApprovalPolicy}), while legacy callers keep using the default.</p>
 */
@Component("mpcApprovalPolicy")
public class MpcApprovalPolicy implements ApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(MpcApprovalPolicy.class);

    /** Cold-wallet tier: any withdrawal at or above this amount uses MPC multi-sig. */
    private static final BigDecimal COLD_WALLET_THRESHOLD = new BigDecimal("50000");

    /** Threshold for cold-wallet MPC signatures. */
    private static final int COLD_WALLET_MPC_THRESHOLD = 3;

    /** Total participants in the cold-wallet MPC pool. */
    private static final int COLD_WALLET_MPC_TOTAL = 5;

    /** Whitelist of permitted withdrawal addresses. */
    private final Set<String> whitelist = new CopyOnWriteArraySet<>();

    /** Threshold policy for the cold-wallet MPC pool. */
    private final ThresholdPolicy coldWalletPolicy =
            new ThresholdPolicy(COLD_WALLET_MPC_THRESHOLD, COLD_WALLET_MPC_TOTAL);

    /**
     * Default constructor.
     */
    public MpcApprovalPolicy() {
        log.info("MpcApprovalPolicy initialised: coldWalletPolicy={}", coldWalletPolicy);
    }

    @Override
    public int getRequiredApprovers(BigDecimal amount, String currency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (isColdWalletTier(amount)) {
            return coldWalletPolicy.getThreshold();
        }
        // Warm wallet: single approver suffices (no MPC).
        return 1;
    }

    @Override
    public boolean isAddressWhitelisted(String address) {
        return address != null && whitelist.contains(address);
    }

    /**
     * Decide whether a withdrawal of the given amount can be signed via MPC
     * given the currently online participants.
     *
     * @param amount             withdrawal amount
     * @param onlineParticipants  currently online MPC participants
     * @return {@code true} iff the amount is in the cold-wallet tier AND the
     *         online participants satisfy the threshold quorum
     */
    public boolean canSign(BigDecimal amount, java.util.List<MpcParticipant> onlineParticipants) {
        Objects.requireNonNull(amount, "amount");
        if (!isColdWalletTier(amount)) {
            return true; // warm wallet, no MPC needed
        }
        boolean quorum = coldWalletPolicy.isQuorumReached(onlineParticipants);
        if (!quorum) {
            log.warn("MPC quorum not reached for cold-wallet withdrawal: online={}",
                    onlineParticipants == null ? 0 : onlineParticipants.size());
        }
        return quorum;
    }

    /**
     * @return the threshold policy governing the cold-wallet MPC pool
     */
    public ThresholdPolicy getColdWalletPolicy() {
        return coldWalletPolicy;
    }

    /**
     * @param amount withdrawal amount
     * @return {@code true} iff the amount triggers the cold-wallet MPC flow
     */
    public boolean isColdWalletTier(BigDecimal amount) {
        return amount != null && amount.compareTo(COLD_WALLET_THRESHOLD) >= 0;
    }

    /**
     * Add an address to the whitelist.
     *
     * @param address wallet address
     */
    public void addToWhitelist(String address) {
        if (address != null && !address.isEmpty()) {
            whitelist.add(address);
        }
    }

    /**
     * Remove an address from the whitelist.
     *
     * @param address wallet address
     */
    public void removeFromWhitelist(String address) {
        if (address != null) {
            whitelist.remove(address);
        }
    }
}
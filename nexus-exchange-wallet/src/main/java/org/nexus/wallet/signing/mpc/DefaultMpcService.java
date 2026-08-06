package org.nexus.wallet.signing.mpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default skeleton implementation of {@link MpcService}.
 *
 * <p>All methods are stubbed and log a TODO marker. Production wiring must
 * integrate a proven MPC threshold-signature protocol such as
 * <strong>GG18</strong> or <strong>GG20</strong> for ECDSA. The implementation
 * must orchestrate distributed key generation (DKG), distributed signing, and
 * proactive key refresh across the participant nodes.</p>
 *
 * <p><b>TODO:</b> integrate GG18/GG20 protocol library, wire participant
 * transport (gRPC/TLS), persist wallet metadata and signing sessions, enforce
 * threshold quorum, and audit all signing rounds.</p>
 */
@Service
public class DefaultMpcService implements MpcService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMpcService.class);

    @Override
    public MpcWallet createMpcWallet(List<String> participants, int threshold) {
        // TODO: validate 1 < threshold <= participants.size()
        // TODO: run GG18/GG20 distributed key generation (DKG) across participants
        // TODO: persist the MpcWallet with the joint public key; private shares never leave their nodes
        log.warn("createMpcWallet not implemented: participants={}, threshold={}", participants, threshold);
        MpcWallet stub = new MpcWallet();
        stub.setParticipants(participants);
        stub.setThreshold(threshold);
        return stub;
    }

    @Override
    public String signTransaction(String walletId, String txData) {
        // TODO: load MpcWallet, initiate a GG18/GG20 signing round with participants
        // TODO: collect threshold signature shares, combine into the final ECDSA signature
        // TODO: persist the MpcSignSession for audit and return the combined signature
        log.warn("signTransaction not implemented: walletId={}, txData={}", walletId, txData);
        return null;
    }

    @Override
    public MpcWallet rotateKey(String walletId) {
        // TODO: load MpcWallet, run proactive key refresh (GG18/GG20 refresh phase)
        // TODO: update lastRotatedAt; the joint public key must remain unchanged
        log.warn("rotateKey not implemented: walletId={}", walletId);
        return null;
    }
}
package org.nexus.signing.mpc;

import java.util.Objects;

/**
 * A participant's key share in an MPC (GG18/GG20) threshold wallet.
 *
 * <p>Each participant holds a private share {@code x_i} of the joint secret
 * key {@code x = sum(x_i)} and a public verification share {@code X_i = x_i * G}
 * on the curve. The joint public key is {@code X = sum(X_i)}. Private shares
 * must never leave their owning node; this class therefore exposes only the
 * public verification share by default and guards the private share behind
 * an explicit accessor for use only inside the signing routine.</p>
 *
 * <p>This is a value object: two shares are equal iff they belong to the same
 * participant and carry the same material.</p>
 */
public final class MpcKeyShare {

    /** Identifier of the participant that owns this share. */
    private final String participantId;

    /** Private key share (hex-encoded big integer). Never serialised off-node. */
    private final String privateShareHex;

    /** Public verification share (hex-encoded curve point). */
    private final String publicShareHex;

    /** Paillier public key material used by GG18/GG20 (hex), or {@code null}. */
    private final String paillierPublicKeyHex;

    /**
     * Construct a key share.
     *
     * @param participantId      owner participant ID
     * @param privateShareHex    private share material (hex)
     * @param publicShareHex     public verification share (hex)
     * @param paillierPublicKeyHex Paillier public key (hex) or {@code null}
     */
    public MpcKeyShare(String participantId,
                       String privateShareHex,
                       String publicShareHex,
                       String paillierPublicKeyHex) {
        this.participantId = Objects.requireNonNull(participantId, "participantId");
        this.privateShareHex = Objects.requireNonNull(privateShareHex, "privateShareHex");
        this.publicShareHex = Objects.requireNonNull(publicShareHex, "publicShareHex");
        this.paillierPublicKeyHex = paillierPublicKeyHex;
    }

    /**
     * @return the owning participant ID
     */
    public String getParticipantId() {
        return participantId;
    }

    /**
     * @return the private share material (hex). Callers MUST NOT persist or log
     *         this value off-node.
     */
    public String getPrivateShareHex() {
        return privateShareHex;
    }

    /**
     * @return the public verification share (hex)
     */
    public String getPublicShareHex() {
        return publicShareHex;
    }

    /**
     * @return the Paillier public key (hex) or {@code null} if not applicable
     */
    public String getPaillierPublicKeyHex() {
        return paillierPublicKeyHex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MpcKeyShare)) return false;
        MpcKeyShare that = (MpcKeyShare) o;
        return participantId.equals(that.participantId)
                && privateShareHex.equals(that.privateShareHex)
                && publicShareHex.equals(that.publicShareHex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(participantId, privateShareHex, publicShareHex);
    }

    @Override
    public String toString() {
        // Intentionally omits privateShareHex to avoid leaking material in logs.
        return "MpcKeyShare{participantId='" + participantId + "', publicShareHex='" + publicShareHex + "'}";
    }
}
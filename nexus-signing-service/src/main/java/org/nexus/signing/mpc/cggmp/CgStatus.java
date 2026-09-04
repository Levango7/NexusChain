package org.nexus.signing.mpc.cggmp;

/**
 * CGGMP21 会话状态快照 DTO（G 批）。
 *
 * <p>对应 {@code CgStatusResponse}，仅含状态标志（不含密钥材料）。</p>
 */
public final class CgStatus {

    private final boolean hasKeygenState;
    private final boolean hasAuxState;
    private final boolean hasSignState;
    private final boolean hasCoreShare;
    private final boolean hasAuxInfo;
    private final boolean hasKeyShare;
    private final boolean success;
    private final String error;

    public CgStatus(
            boolean hasKeygenState,
            boolean hasAuxState,
            boolean hasSignState,
            boolean hasCoreShare,
            boolean hasAuxInfo,
            boolean hasKeyShare,
            boolean success,
            String error) {
        this.hasKeygenState = hasKeygenState;
        this.hasAuxState = hasAuxState;
        this.hasSignState = hasSignState;
        this.hasCoreShare = hasCoreShare;
        this.hasAuxInfo = hasAuxInfo;
        this.hasKeyShare = hasKeyShare;
        this.success = success;
        this.error = error == null ? "" : error;
    }

    public boolean isHasKeygenState() { return hasKeygenState; }
    public boolean isHasAuxState() { return hasAuxState; }
    public boolean isHasSignState() { return hasSignState; }
    public boolean isHasCoreShare() { return hasCoreShare; }
    public boolean isHasAuxInfo() { return hasAuxInfo; }
    public boolean isHasKeyShare() { return hasKeyShare; }
    public boolean isSuccess() { return success; }
    public String getError() { return error; }
}

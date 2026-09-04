package org.nexus.signing.mpc.cggmp;

import java.util.Collections;
import java.util.List;

/**
 * CGGMP21 协议阶段泵动结果 DTO（G 批）。
 *
 * <p>keygen / aux 阶段共用。sign 阶段见 {@link CgSignPumpResult}。</p>
 */
public final class CgPumpResult {

    private final List<CgRelayMessageDto> outgoing;
    private final boolean finished;
    private final String aggregatePublicKey;
    private final boolean success;
    private final String error;

    public CgPumpResult(
            List<CgRelayMessageDto> outgoing,
            boolean finished,
            String aggregatePublicKey,
            boolean success,
            String error) {
        this.outgoing = outgoing == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(outgoing);
        this.finished = finished;
        this.aggregatePublicKey = aggregatePublicKey;
        this.success = success;
        this.error = error == null ? "" : error;
    }

    public static CgPumpResult failure(String error) {
        return new CgPumpResult(Collections.emptyList(), false, null, false, error);
    }

    public List<CgRelayMessageDto> getOutgoing() { return outgoing; }
    public boolean isFinished() { return finished; }
    public String getAggregatePublicKey() { return aggregatePublicKey; }
    public boolean isSuccess() { return success; }
    public String getError() { return error; }
}

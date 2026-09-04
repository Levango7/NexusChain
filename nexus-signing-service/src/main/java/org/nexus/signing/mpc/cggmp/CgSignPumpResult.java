package org.nexus.signing.mpc.cggmp;

import java.util.Collections;
import java.util.List;

/**
 * CGGMP21 sign 阶段泵动结果 DTO（G 批）。
 *
 * <p>完成时携带 32 字节大端 hex 编码的 r / s。</p>
 */
public final class CgSignPumpResult {

    private final List<CgRelayMessageDto> outgoing;
    private final boolean finished;
    private final String rHex;
    private final String sHex;
    private final boolean success;
    private final String error;

    public CgSignPumpResult(
            List<CgRelayMessageDto> outgoing,
            boolean finished,
            String rHex,
            String sHex,
            boolean success,
            String error) {
        this.outgoing = outgoing == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(outgoing);
        this.finished = finished;
        this.rHex = rHex;
        this.sHex = sHex;
        this.success = success;
        this.error = error == null ? "" : error;
    }

    public static CgSignPumpResult failure(String error) {
        return new CgSignPumpResult(Collections.emptyList(), false, null, null, false, error);
    }

    public List<CgRelayMessageDto> getOutgoing() { return outgoing; }
    public boolean isFinished() { return finished; }
    public String getRHex() { return rHex; }
    public String getSHex() { return sHex; }
    public boolean isSuccess() { return success; }
    public String getError() { return error; }
}

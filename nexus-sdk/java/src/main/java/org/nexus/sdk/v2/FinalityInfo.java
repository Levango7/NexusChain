package org.nexus.sdk.v2;

/**
 * 最终性信息（SDK 侧载体，对应 gateway FinalityService.FinalityInfo 的 JSON 结构）。
 *
 * <p>字段与网关契约一致：{@code finality_status / confirmations / threshold / progress_percent}。</p>
 */
public final class FinalityInfo {

    private FinalityStatus status = FinalityStatus.UNKNOWN;
    private long confirmations;
    private long threshold;
    private int progressPercent;
    private String note;

    public FinalityInfo() {}

    public FinalityStatus getStatus() { return status; }
    public void setStatus(FinalityStatus status) { this.status = status; }

    public long getConfirmations() { return confirmations; }
    public void setConfirmations(long confirmations) { this.confirmations = confirmations; }

    public long getThreshold() { return threshold; }
    public void setThreshold(long threshold) { this.threshold = threshold; }

    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    /** 是否已达到不可逆状态（大额结算放行条件）。 */
    public boolean isFinalized() { return status == FinalityStatus.FINALIZED; }

    @Override
    public String toString() {
        return "FinalityInfo{status=" + status + ", confirmations=" + confirmations
                + "/" + threshold + ", progress=" + progressPercent + "%}";
    }
}

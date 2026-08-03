package org.nexus.wallet.pool;

public class NonceState {
    private String TranHash;
    private long nonce;
    private long datetime;

    public NonceState() {
    }

    public NonceState(String tranHash,  long nonce, long datetime ) {
        TranHash = tranHash;
        this.nonce = nonce;
        this.datetime = datetime;
    }

    public String getTranHash() {
        return TranHash;
    }

    public void setTranHash(String tranHash) {
        TranHash = tranHash;
    }

    public long getNonce() {
        return nonce;
    }

    public void setNonce(long nonce) {
        this.nonce = nonce;
    }

    public long getDatetime() {
        return datetime;
    }

    public void setDatetime(long datetime) {
        this.datetime = datetime;
    }
}

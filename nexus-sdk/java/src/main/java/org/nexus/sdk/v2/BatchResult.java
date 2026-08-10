package org.nexus.sdk.v2;

import java.util.List;

/**
 * 批量支付结果（v2 SDK）。
 */
public final class BatchResult {

    private final List<Succeeded> succeeded;
    private final List<Failed> failed;
    private final int totalCount;

    public BatchResult(List<Succeeded> succeeded, List<Failed> failed, int totalCount) {
        this.succeeded = succeeded;
        this.failed = failed;
        this.totalCount = totalCount;
    }

    public List<Succeeded> succeeded() { return succeeded; }
    public List<Failed> failed() { return failed; }
    public int totalCount() { return totalCount; }
    public int succeededCount() { return succeeded.size(); }
    public int failedCount() { return failed.size(); }
    public boolean allSucceeded() { return failed.isEmpty(); }

    public static final class Succeeded {
        private final int index;
        private final long id;
        private final String orderNo;
        private final String status;

        public Succeeded(int index, long id, String orderNo, String status) {
            this.index = index;
            this.id = id;
            this.orderNo = orderNo;
            this.status = status;
        }

        public int index() { return index; }
        public long id() { return id; }
        public String orderNo() { return orderNo; }
        public String status() { return status; }
    }

    public static final class Failed {
        private final int index;
        private final String code;
        private final String message;

        public Failed(int index, String code, String message) {
            this.index = index;
            this.code = code;
            this.message = message;
        }

        public int index() { return index; }
        public String code() { return code; }
        public String message() { return message; }
    }
}
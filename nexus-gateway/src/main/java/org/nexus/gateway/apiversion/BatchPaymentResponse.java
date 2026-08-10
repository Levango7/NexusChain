package org.nexus.gateway.apiversion;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * v2 批量创建支付响应（P4-T7）。
 *
 * <p>对应端点 {@code POST /api/v2/payments/batch} 的响应：</p>
 * <pre>{@code
 * {
 *   "succeeded": [ { "id": 1, "orderNo": "..." }, ... ],
 *   "failed":    [ { "index": 2, "error": { "code": "...", "message": "..." } }, ... ],
 *   "totalCount": 3,
 *   "succeededCount": 2,
 *   "failedCount": 1
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchPaymentResponse {

    private final List<SucceededItem> succeeded;
    private final List<FailedItem> failed;
    private final int totalCount;
    private final int succeededCount;
    private final int failedCount;

    public BatchPaymentResponse(List<SucceededItem> succeeded, List<FailedItem> failed) {
        this.succeeded = succeeded;
        this.failed = failed;
        this.totalCount = (succeeded == null ? 0 : succeeded.size())
                + (failed == null ? 0 : failed.size());
        this.succeededCount = succeeded == null ? 0 : succeeded.size();
        this.failedCount = failed == null ? 0 : failed.size();
    }

    public List<SucceededItem> getSucceeded() { return succeeded; }
    public List<FailedItem> getFailed() { return failed; }
    public int getTotalCount() { return totalCount; }
    public int getSucceededCount() { return succeededCount; }
    public int getFailedCount() { return failedCount; }

    /** 成功项 */
    public static class SucceededItem {
        private final int index;
        private final Long id;
        private final String orderNo;
        private final String status;

        public SucceededItem(int index, Long id, String orderNo, String status) {
            this.index = index;
            this.id = id;
            this.orderNo = orderNo;
            this.status = status;
        }

        public int getIndex() { return index; }
        public Long getId() { return id; }
        public String getOrderNo() { return orderNo; }
        public String getStatus() { return status; }
    }

    /** 失败项 */
    public static class FailedItem {
        private final int index;
        private final V2ErrorResponse.ErrorBody error;

        public FailedItem(int index, V2ErrorResponse.ErrorBody error) {
            this.index = index;
            this.error = error;
        }

        public int getIndex() { return index; }
        public V2ErrorResponse.ErrorBody getError() { return error; }
    }
}
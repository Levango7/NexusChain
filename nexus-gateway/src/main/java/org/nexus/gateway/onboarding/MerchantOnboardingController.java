package org.nexus.gateway.onboarding;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商户入驻 REST API。
 *
 * <p>端点说明：</p>
 * <ul>
 *   <li>{@code POST /api/v1/onboarding/apply} — 提交入驻申请（公开）</li>
 *   <li>{@code GET /api/v1/onboarding/status/{applicationId}} — 查询申请状态（公开）</li>
 *   <li>{@code POST /api/v1/onboarding/review/{applicationId}} — 领取审核（ADMIN）</li>
 *   <li>{@code GET /api/v1/onboarding/applications} — 列出待审核申请（ADMIN）</li>
 *   <li>{@code POST /api/v1/onboarding/approve/{applicationId}} — 批准申请（ADMIN）</li>
 *   <li>{@code POST /api/v1/onboarding/reject/{applicationId}} — 拒绝申请（ADMIN）</li>
 *   <li>{@code POST /api/v1/onboarding/suspend/{applicationId}} — 暂停商户服务（ADMIN）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/onboarding")
public class MerchantOnboardingController {

    private final MerchantReviewService reviewService;

    public MerchantOnboardingController(MerchantReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 提交入驻申请。
     *
     * <p>P1-3：参数 DTO 已加 Bean Validation 注解，此处通过 {@code @Valid} 触发校验，
     * 校验失败由全局异常处理器返回 400。</p>
     *
     * @param request 申请信息
     * @return 创建的申请记录（201）
     */
    @PostMapping("/apply")
    public ResponseEntity<MerchantApplication> apply(
            @Valid @RequestBody MerchantReviewService.ApplicationRequest request) {
        MerchantApplication application = reviewService.submitApplication(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(application);
    }

    /**
     * 查询申请状态。
     *
     * @param applicationId 申请 ID
     * @return 申请记录（含状态信息）
     */
    @GetMapping("/status/{applicationId}")
    public ResponseEntity<MerchantApplication> getStatus(@PathVariable Long applicationId) {
        try {
            MerchantApplication application = reviewService.getApplicationStatus(applicationId);
            return ResponseEntity.ok(application);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 审核人员领取申请（PENDING → REVIEWING）。
     *
     * @param applicationId 申请 ID
     * @param request       审核请求（含 reviewerId）
     * @return 更新后的申请记录
     */
    @PostMapping("/review/{applicationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> startReview(@PathVariable Long applicationId,
                                          @RequestBody ReviewRequest request) {
        try {
            MerchantApplication application = reviewService.startReview(applicationId, request.getReviewerId());
            return ResponseEntity.ok(application);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出待审核申请。
     *
     * <p>P1-4：{@code ApplicationStatus.valueOf} 对非法值会抛
     * {@link IllegalArgumentException}（原先导致 500）。现捕获并返回 400 Bad Request。</p>
     *
     * @param status 申请状态筛选（可选，默认 PENDING）
     * @return 申请列表，或状态非法时的 400
     */
    @GetMapping("/applications")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> listApplications(
            @RequestParam(required = false) String status) {
        if (status != null && !status.isBlank()) {
            ApplicationStatus statusEnum;
            try {
                statusEnum = ApplicationStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid status: " + status));
            }
            return ResponseEntity.ok(reviewService.listApplicationsByStatus(statusEnum));
        }
        return ResponseEntity.ok(reviewService.listPendingApplications());
    }

    /**
     * 批准申请 — 触发自动开通流程。
     *
     * @param applicationId 申请 ID
     * @param request       批准请求（含 reviewerId, reviewComment）
     * @return 更新后的申请记录（含 merchantId）
     */
    @PostMapping("/approve/{applicationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> approve(@PathVariable Long applicationId,
                                     @RequestBody ApproveRequest request) {
        try {
            MerchantApplication application = reviewService.approveApplication(
                    applicationId, request.getReviewerId(), request.getReviewComment());
            return ResponseEntity.ok(application);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("error", e.getMessage());
            errorBody.put("applicationId", applicationId);
            return ResponseEntity.badRequest().body(errorBody);
        }
    }

    /**
     * 拒绝申请。
     *
     * @param applicationId 申请 ID
     * @param request       拒绝请求（含 reviewerId, reviewComment）
     * @return 更新后的申请记录
     */
    @PostMapping("/reject/{applicationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> reject(@PathVariable Long applicationId,
                                    @RequestBody RejectRequest request) {
        try {
            MerchantApplication application = reviewService.rejectApplication(
                    applicationId, request.getReviewerId(), request.getReviewComment());
            return ResponseEntity.ok(application);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 暂停已开通商户的服务。
     *
     * @param applicationId 申请 ID
     * @param request       暂停请求（含 reason）
     * @return 更新后的申请记录
     */
    @PostMapping("/suspend/{applicationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> suspend(@PathVariable Long applicationId,
                                     @RequestBody SuspendRequest request) {
        try {
            MerchantApplication application = reviewService.suspendMerchant(
                    applicationId, request.getReason());
            return ResponseEntity.ok(application);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // --- Request DTOs ---

    public static class ReviewRequest {
        private String reviewerId;

        public String getReviewerId() { return reviewerId; }
        public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }
    }

    public static class ApproveRequest {
        private String reviewerId;
        private String reviewComment;

        public String getReviewerId() { return reviewerId; }
        public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }

        public String getReviewComment() { return reviewComment; }
        public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    }

    public static class RejectRequest {
        private String reviewerId;
        private String reviewComment;

        public String getReviewerId() { return reviewerId; }
        public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }

        public String getReviewComment() { return reviewComment; }
        public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    }

    public static class SuspendRequest {
        private String reason;

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
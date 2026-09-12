package org.nexus.gateway.controller.v2;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.nexus.gateway.OrderService;
import org.nexus.gateway.apiversion.BatchPaymentRequest;
import org.nexus.gateway.apiversion.BatchPaymentResponse;
import org.nexus.gateway.apiversion.V2ErrorCode;
import org.nexus.gateway.apiversion.V2ErrorResponse;
import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.model.PaymentOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v2 支付 API（P4-T7）。
 *
 * <p>核心增强：批量创建支付端点 {@code POST /api/v2/payments/batch}。</p>
 *
 * <p>批量端点支持两种失败处理策略：</p>
 * <ul>
 *   <li>{@code ALL_OR_NOTHING}（默认）：任一失败则全部回滚，返回 422 + 失败项详情</li>
 *   <li>{@code PARTIAL}：成功的提交、失败的逐项返回错误</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v2/payments")
@Tag(name = "Payment v2", description = "v2 支付 API：批量操作、统一错误码")
public class PaymentV2Controller {

    private static final Logger log = LoggerFactory.getLogger(PaymentV2Controller.class);

    private final OrderService orderService;

    public PaymentV2Controller(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 批量创建支付（P4-T7 v2 核心增强）。
     *
     * <p>一次提交最多 {@link BatchPaymentRequest#MAX_BATCH_SIZE} 笔支付。
     * 单笔支付的字段语义与 v1 {@code POST /api/v1/orders} 一致。</p>
     *
     * <p>响应：</p>
     * <ul>
     *   <li>201 + 全部成功列表（{@code ALL_OR_NOTHING} 模式下全部成功时）</li>
     *   <li>207 + 成功/失败混合列表（{@code PARTIAL} 模式下部分成功时）</li>
     *   <li>422 + 失败列表（{@code ALL_OR_NOTHING} 模式下任一失败时，全部回滚）</li>
     *   <li>400 + 校验错误（请求体不合法时）</li>
     * </ul>
     */
    @Operation(summary = "Batch create payments (v2)")
    @PostMapping("/batch")
    public ResponseEntity<Object> batchCreate(@Valid @RequestBody BatchPaymentRequest request) {
        List<BatchPaymentRequest.PaymentItem> items = request.getPayments();
        BatchPaymentRequest.FailureStrategy strategy = request.getOnFailure();
        if (strategy == null) {
            strategy = BatchPaymentRequest.FailureStrategy.ALL_OR_NOTHING;
        }

        log.info("Batch create payments: count={}, strategy={}", items.size(), strategy);

        // 事务下沉（2026-09-11 质量审查 B5）：原方法级 @Transactional 迁至
        // OrderService.batchCreate（Service 层事务边界）。Controller 只做
        // 协议映射；ALL_OR_NOTHING 失败时 Service 抛 BatchCreateException
        // 触发回滚，由 GlobalExceptionHandlerV2 转 422。
        List<CreateOrderRequest> reqs = new ArrayList<>();
        for (BatchPaymentRequest.PaymentItem item : items) {
            reqs.add(toItemRequest(item));
        }
        boolean allOrNothing = strategy == BatchPaymentRequest.FailureStrategy.ALL_OR_NOTHING;
        OrderService.BatchCreateResult result = orderService.batchCreate(reqs, allOrNothing);

        List<BatchPaymentResponse.SucceededItem> succeeded = new ArrayList<>();
        for (OrderService.BatchCreateResult.OrderCreated oc : result.orders()) {
            PaymentOrder order = oc.order();
            succeeded.add(new BatchPaymentResponse.SucceededItem(
                    oc.originalIndex(), order.getId(), order.getOrderNo(), order.getStatus().name()));
        }
        List<BatchPaymentResponse.FailedItem> failed = new ArrayList<>();
        for (OrderService.BatchCreateResult.OrderFailed of : result.failures()) {
            V2ErrorResponse.ErrorBody error = V2ErrorResponse.of(
                    V2ErrorCode.INTERNAL_ERROR.getCode(),
                    of.errorMessage(), null).getError();
            failed.add(new BatchPaymentResponse.FailedItem(of.originalIndex(), error));
        }

        BatchPaymentResponse body = new BatchPaymentResponse(succeeded, failed);

        if (failed.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }
        // 部分成功
        return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(body);
    }

    private CreateOrderRequest toItemRequest(BatchPaymentRequest.PaymentItem item) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setMerchantId(String.valueOf(item.getMerchantId()));
        req.setAmount(item.getAmount());
        req.setTokenSymbol(item.getTokenSymbol() != null ? item.getTokenSymbol() : "NEX");
        req.setDescription(item.getDescription());
        req.setPayerAddress(item.getPayerAddress());
        req.setNotifyUrl(item.getNotifyUrl());
        req.setIdempotencyKey(item.getIdempotencyKey());
        return req;
    }
    // BatchFailedException 已随 B5 事务下沉移至 OrderService.BatchCreateException
    // （2026-09-11 质量审查：批量事务边界归 Service 层，异常信号同层内聚）
}
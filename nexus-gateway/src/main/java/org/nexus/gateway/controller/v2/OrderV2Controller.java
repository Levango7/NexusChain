package org.nexus.gateway.controller.v2;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.OrderService;
import org.nexus.gateway.PaymentService;
import org.nexus.gateway.apiversion.CursorPageRequest;
import org.nexus.gateway.apiversion.CursorPageResponse;
import org.nexus.gateway.apiversion.CursorPagination;
import org.nexus.gateway.apiversion.FieldsFilter;
import org.nexus.gateway.apiversion.V2ErrorCode;
import org.nexus.gateway.apiversion.V2ErrorResponse;
import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.dto.PaymentResult;
import org.nexus.gateway.model.PaymentOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * v2 订单 API（P4-T7）。
 *
 * <p>相比 v1 的增强：</p>
 * <ul>
 *   <li>游标分页：{@code GET /api/v2/orders?cursor=...&pageSize=20}</li>
 *   <li>字段筛选：{@code GET /api/v2/orders/{id}?fields=id,amount,status}</li>
 *   <li>统一错误响应：{@link V2ErrorResponse}</li>
 * </ul>
 *
 * <p>v1 端点（{@link org.nexus.gateway.controller.PaymentController}）保持原行为不变，
 * 仅由 {@link org.nexus.gateway.apiversion.ApiVersionFilter} 注入 Deprecation 头。</p>
 */
@RestController
@RequestMapping("/api/v2/orders")
@Tag(name = "Order v2", description = "v2 订单 API：游标分页、字段筛选、统一错误码")
public class OrderV2Controller {

    private static final Logger log = LoggerFactory.getLogger(OrderV2Controller.class);

    /** PaymentOrder 允许筛选的字段白名单 */
    private static final Set<String> ORDER_ALLOWED_FIELDS = Set.of(
            "id", "orderno", "merchantid", "tokensymbol", "amount",
            "description", "payeraddress", "payeeaddress", "chaintxhash",
            "status", "checkouttoken", "expiresat", "createdat", "updatedat", "paidat"
    );

    private final OrderService orderService;
    private final PaymentService paymentService;

    public OrderV2Controller(OrderService orderService,
                             PaymentService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    /**
     * 创建订单（v2）。
     *
     * <p>与 v1 行为一致，但响应包裹在统一信封内，错误时返回 {@link V2ErrorResponse}。</p>
     */
    @Operation(summary = "Create order (v2)")
    @PostMapping
    public ResponseEntity<PaymentOrder> createOrder(@RequestBody CreateOrderRequest request) {
        PaymentOrder order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    /**
     * 查询订单详情（v2）——支持字段筛选。
     *
     * @param id     订单 ID
     * @param fields 字段筛选（如 "id,amount,status"）；缺省返回完整对象
     */
    @Operation(summary = "Get order by id (v2, with fields selection)")
    @GetMapping("/{id}")
    public ResponseEntity<Object> getOrder(@PathVariable Long id,
                                            @RequestParam(value = "fields", required = false) String fields) {
        Set<String> selected = FieldsFilter.parse(fields);
        // 字段校验
        Set<String> invalid = FieldsFilter.validateFields(selected, ORDER_ALLOWED_FIELDS);
        if (!invalid.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("invalidFields", invalid);
            details.put("allowedFields", ORDER_ALLOWED_FIELDS);
            return ResponseEntity.badRequest()
                    .body(V2ErrorResponse.of(V2ErrorCode.INVALID_FIELDS.getCode(),
                            "Unknown fields: " + invalid, details));
        }

        Optional<PaymentOrder> opt = orderService.findById(id);
        if (opt.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("orderId", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(V2ErrorResponse.of(V2ErrorCode.ORDER_NOT_FOUND.getCode(),
                            "Order with id=" + id + " not found", details));
        }
        Object filtered = FieldsFilter.apply(opt.get(), selected);

        return ResponseEntity.ok(filtered);
    }

    /**
     * 订单列表（v2）——游标分页 + 字段筛选。
     *
     * <p>按 id 升序游标分页。游标为上一页最后一项的 id（base64 编码）。</p>
     *
     * @param cursor   游标（首页请求不传）
     * @param pageSize 每页条数（默认 20，最大 100）
     * @param fields   字段筛选
     * @param merchantId 可选商户 ID 过滤
     */
    @Operation(summary = "List orders (v2, cursor pagination + fields selection)")
    @GetMapping
    public ResponseEntity<Object> listOrders(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "fields", required = false) String fields,
            @RequestParam(value = "merchantId", required = false) Long merchantId,
            HttpServletRequest request) {

        CursorPageRequest pageReq;
        try {
            pageReq = CursorPagination.parseRequest(cursor, pageSize);
        } catch (IllegalArgumentException e) {
            Map<String, Object> details = new HashMap<>();
            details.put("cursor", cursor);
            return ResponseEntity.badRequest()
                    .body(V2ErrorResponse.of(V2ErrorCode.INVALID_CURSOR.getCode(),
                            e.getMessage(), details));
        }

        Set<String> selected = FieldsFilter.parse(fields);
        Set<String> invalid = FieldsFilter.validateFields(selected, ORDER_ALLOWED_FIELDS);
        if (!invalid.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("invalidFields", invalid);
            return ResponseEntity.badRequest()
                    .body(V2ErrorResponse.of(V2ErrorCode.INVALID_FIELDS.getCode(),
                            "Unknown fields: " + invalid, details));
        }

        // 查询 pageSize + 1 条以判断 hasMore
        Long afterId = null;
        if (pageReq.getDecodedCursor() != null) {
            try {
                afterId = Long.parseLong(pageReq.getDecodedCursor());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Cursor does not encode a valid id");
            }
        }
        int querySize = pageReq.getPageSize() + 1;
        List<PaymentOrder> items = orderService.findOrdersWithCursor(afterId, querySize, merchantId);

        CursorPageResponse<PaymentOrder> page = CursorPagination.buildPage(
                items, pageReq, PaymentOrder::getId);

        // 应用字段筛选
        List<Object> filteredData = FieldsFilter.applyToList(page.getData(), selected);

        // 构造响应（保持 CursorPageResponse 结构，data 替换为筛选后的列表）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", filteredData);
        body.put("nextCursor", page.getNextCursor());
        body.put("hasMore", page.isHasMore());
        body.put("count", page.getCount());
        body.put("pageSize", page.getPageSize());
        return ResponseEntity.ok(body);
    }

    /**
     * 发起支付（v2）——与 v1 行为一致，但错误响应统一。
     */
    @Operation(summary = "Initiate payment (v2)")
    @PostMapping("/{id}/pay")
    public ResponseEntity<Object> pay(@PathVariable Long id,
                                       @RequestBody PayRequest request) {
        if (request.getPayerAddress() == null || request.getPayerAddress().isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("field", "payerAddress");
            return ResponseEntity.badRequest()
                    .body(V2ErrorResponse.of(V2ErrorCode.BAD_REQUEST.getCode(),
                            "payerAddress must not be empty", details));
        }
        try {
            PaymentResult result = paymentService.initiatePayment(id, request.getPayerAddress());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> details = new HashMap<>();
            details.put("orderId", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(V2ErrorResponse.of(V2ErrorCode.ORDER_NOT_FOUND.getCode(),
                            e.getMessage(), details));
        }
    }

    /**
     * 退款（v2）——与 v1 行为一致，但错误响应统一。
     */
    @Operation(summary = "Refund order (v2)")
    @PostMapping("/{id}/refund")
    public ResponseEntity<Object> refund(@PathVariable Long id,
                                          @RequestBody RefundRequest request) {
        try {
            var refund = paymentService.refund(id, request.getAmount(), request.getReason());
            return ResponseEntity.status(HttpStatus.CREATED).body(refund);
        } catch (IllegalArgumentException e) {
            Map<String, Object> details = new HashMap<>();
            details.put("orderId", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(V2ErrorResponse.of(V2ErrorCode.ORDER_NOT_FOUND.getCode(),
                            e.getMessage(), details));
        } catch (IllegalStateException e) {
            Map<String, Object> details = new HashMap<>();
            details.put("orderId", id);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(V2ErrorResponse.of(V2ErrorCode.ILLEGAL_STATE_TRANSITION.getCode(),
                            e.getMessage(), details));
        }
    }


    // --- 内嵌 DTO ---

    public static class PayRequest {
        private String payerAddress;
        public String getPayerAddress() { return payerAddress; }
        public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }
    }

    public static class RefundRequest {
        private BigDecimal amount;
        private String reason;
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
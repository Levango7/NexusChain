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
import org.nexus.gateway.orchestration.settlement.FinalityService;
import org.nexus.gateway.orchestration.settlement.FinalityStatus;
import org.nexus.gateway.security.MerchantOwnershipException;
import org.nexus.gateway.security.MerchantOwnershipGuard;
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
            "status", "checkouttoken", "expiresat", "createdat", "updatedat", "paidat",
            "finality"  // 支付最终性状态（叠加字段，由 FinalityService 推导得出
    );

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final FinalityService finalityService;
    private final MerchantOwnershipGuard ownershipGuard;

    public OrderV2Controller(OrderService orderService,
                             PaymentService paymentService,
                             FinalityService finalityService,
                             MerchantOwnershipGuard ownershipGuard) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.finalityService = finalityService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 创建订单（v2）。
     *
     * <p>与 v1 行为一致，但响应包裹在统一信封内，错误时返回 {@link V2ErrorResponse}。</p>
     */
    @Operation(summary = "Create order (v2)")
    @PostMapping
    public ResponseEntity<PaymentOrder> createOrder(@RequestBody CreateOrderRequest request,
                                                    HttpServletRequest httpRequest) {
        // P0-4：订单归属以认证上下文为准，不信任请求体中的 merchantId
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        request.setMerchantId(String.valueOf(callerMerchantId));
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
                                            @RequestParam(value = "fields", required = false) String fields,
                                            HttpServletRequest request) {
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

        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        Optional<PaymentOrder> opt = orderService.findById(id);
        if (opt.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("orderId", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(V2ErrorResponse.of(V2ErrorCode.ORDER_NOT_FOUND.getCode(),
                            "Order with id=" + id + " not found", details));
        }
        ownershipGuard.requireOwned(callerMerchantId, opt.get().getMerchantId(), "order", id);
        Object filtered = FieldsFilter.apply(opt.get(), selected);

        // 若客户端请求了 finality 字段（或不过滤字段时默认叠加），
        // 则查询链上最终性状态并合并到响应中（不影响现有字段筛选逻辑）
        if (selected == null || selected.isEmpty() || selected.contains("finality")) {
            PaymentOrder order = opt.get();
            if (order != null && order.getChainTxHash() != null
                    && !order.getChainTxHash().isEmpty()) {
                FinalityService.FinalityInfo fi = finalityService.getFinality(order.getChainTxHash());
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("finality_status", fi.status());
                meta.put("confirmations", fi.confirmations());
                meta.put("threshold", fi.threshold());
                meta.put("progress_percent", fi.progressPercent());

                // 如果 filtered 是 Map，直接叠加；否则单独构建包装
                if (filtered instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> enriched = new LinkedHashMap<>((Map<String, Object>) filtered);
                    enriched.put("finality", meta);
                    filtered = enriched;
                }
            }
        }

        return ResponseEntity.ok(filtered);
    }

    /**
     * 获取指定订单的支付最终性状态（NexFinality 网关原型）。
     *
     * <p>返回三层最终化状态：OPTIMISTIC / FINALIZING / FINALIZED，
     * 供商户侧根据结算金额选择是否立即发货或等待最终性保证。</p>
     */
    @Operation(summary = "Get order finality status (NexFinality v2 prototype)")
    @GetMapping("/{id}/finality")
    public ResponseEntity<Object> getOrderFinality(@PathVariable Long id,
                                                   HttpServletRequest request) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        Optional<PaymentOrder> opt = orderService.findById(id);
        if (opt.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("orderId", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(V2ErrorResponse.of(V2ErrorCode.ORDER_NOT_FOUND.getCode(),
                            "Order with id=" + id + " not found", details));
        }
        ownershipGuard.requireOwned(callerMerchantId, opt.get().getMerchantId(), "order", id);
        PaymentOrder order = opt.get();
        if (order.getChainTxHash() == null || order.getChainTxHash().isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("order_id", id);
            details.put("reason", "on-chain transaction hash not yet assigned");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(V2ErrorResponse.of("FINALITY_NOT_READY",
                            "On-chain tx hash not assigned to order yet", details));
        }
        FinalityService.FinalityInfo fi = finalityService.getFinality(order.getChainTxHash());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", id);
        body.put("chain_tx_hash", order.getChainTxHash());
        body.put("finality_status", fi.status());
        body.put("confirmations", fi.confirmations());
        body.put("threshold", fi.threshold());
        body.put("progress_percent", fi.progressPercent());
        body.put("note", fi.note());
        return ResponseEntity.ok(body);
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

        // P0-4：列表必须限定在认证商户范围内；请求参数与认证身份不一致时拒绝，
        // 不传参数时默认过滤为调用方自己的订单，杜绝跨商户枚举。
        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        if (merchantId != null && !merchantId.equals(callerMerchantId)) {
            throw new MerchantOwnershipException(
                    "Access denied: merchantId filter does not match the authenticated merchant");
        }
        merchantId = callerMerchantId;

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
                                       @RequestBody PayRequest request,
                                       HttpServletRequest httpRequest) {
        ownershipGuard.requireMerchantId(httpRequest);
        requireOrderOwnership(id, httpRequest);
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
                                          @RequestBody RefundRequest request,
                                          HttpServletRequest httpRequest) {
        requireOrderOwnership(id, httpRequest);
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


    // --- 归属校验 ---

    /**
     * P0-4 修复：订单归属校验（fail-closed）。属性缺失、无法解析或不一致均抛出
     * {@link org.nexus.gateway.security.MerchantOwnershipException}（由
     * V2ExceptionHandler 映射为 403）。
     */
    private void requireOrderOwnership(Long orderId, HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        PaymentOrder order = orderService.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        ownershipGuard.requireOwned(callerMerchantId, order.getMerchantId(), "order", orderId);
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
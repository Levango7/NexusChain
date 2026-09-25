package org.nexus.gateway.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 账户管理 REST API — 商户虚拟账户操作端点。
 *
 * <p>提供余额查询、充值、提现、冻结、解冻、流水查询等功能。
 * 路径前缀：{@code /api/v1/accounts}</p>
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Account", description = "商户虚拟账户管理：余额查询/充值/提现/冻结/解冻/流水查询")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;
    private final AccountTransactionRepository transactionRepository;

    public AccountController(AccountService accountService,
                             AccountTransactionRepository transactionRepository) {
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
    }

    /**
     * 查询商户账户余额。
     *
     * @param merchantId 商户 ID
     * @return 200 + 余额信息（balance, frozen, reserve）
     */
    @Operation(summary = "查询商户账户余额")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    @GetMapping("/{merchantId}")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable Long merchantId) {
        Map<String, Object> balance = accountService.getBalance(merchantId);
        return ResponseEntity.ok(balance);
    }

    /**
     * 查询商户账户流水（分页）。
     *
     * @param merchantId 商户 ID
     * @param page 页码（从 0 开始，默认 0）
     * @param size 每页条数（默认 20）
     * @return 200 + 流水分页结果
     */
    @Operation(summary = "查询商户账户流水")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    @GetMapping("/{merchantId}/transactions")
    public ResponseEntity<Map<String, Object>> getTransactions(
            @PathVariable Long merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<AccountTransaction> txPage =
                transactionRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("page", page);
        result.put("size", size);
        result.put("total", txPage.getTotalElements());
        result.put("totalPages", txPage.getTotalPages());
        result.put("transactions", txPage.getContent().stream()
                .map(this::toTransactionMap)
                .collect(Collectors.toList()));

        return ResponseEntity.ok(result);
    }

    /**
     * 充值（管理员操作）。
     *
     * @param merchantId 商户 ID
     * @param body 充值请求体
     * @return 200 + 充值后的余额信息
     */
    @Operation(summary = "充值（管理员操作）")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{merchantId}/deposit")
    public ResponseEntity<Map<String, Object>> deposit(
            @PathVariable Long merchantId,
            @RequestBody DepositRequest body) {

        BigDecimal amount = new BigDecimal(body.getAmount());
        MerchantAccount account = accountService.deposit(merchantId, amount, body.getOrderId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("accountId", account.getAccountId());
        result.put("balance", account.getBalance());
        result.put("status", account.getStatus().name());

        return ResponseEntity.ok(result);
    }

    /**
     * 提现。
     *
     * @param merchantId 商户 ID
     * @param body 提现请求体
     * @return 200 + 提现后的余额信息
     */
    @Operation(summary = "提现")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    @PostMapping("/{merchantId}/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(
            @PathVariable Long merchantId,
            @RequestBody WithdrawRequest body) {

        BigDecimal amount = new BigDecimal(body.getAmount());
        MerchantAccount account = accountService.withdraw(merchantId, amount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("accountId", account.getAccountId());
        result.put("balance", account.getBalance());
        result.put("status", account.getStatus().name());

        return ResponseEntity.ok(result);
    }

    /**
     * 冻结金额。
     *
     * @param merchantId 商户 ID
     * @param body 冻结请求体
     * @return 200 + 冻结后的余额信息
     */
    @Operation(summary = "冻结金额")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{merchantId}/freeze")
    public ResponseEntity<Map<String, Object>> freeze(
            @PathVariable Long merchantId,
            @RequestBody FreezeRequest body) {

        BigDecimal amount = new BigDecimal(body.getAmount());
        MerchantAccount account = accountService.freeze(merchantId, amount, body.getReason());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("accountId", account.getAccountId());
        result.put("balance", account.getBalance());
        result.put("status", account.getStatus().name());

        return ResponseEntity.ok(result);
    }

    /**
     * 解冻金额。
     *
     * @param merchantId 商户 ID
     * @param body 解冻请求体
     * @return 200 + 解冻后的余额信息
     */
    @Operation(summary = "解冻金额")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{merchantId}/unfreeze")
    public ResponseEntity<Map<String, Object>> unfreeze(
            @PathVariable Long merchantId,
            @RequestBody UnfreezeRequest body) {

        BigDecimal amount = new BigDecimal(body.getAmount());
        MerchantAccount account = accountService.unfreeze(merchantId, amount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("accountId", account.getAccountId());
        result.put("balance", account.getBalance());
        result.put("status", account.getStatus().name());

        return ResponseEntity.ok(result);
    }

    // === 异常处理 ===

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_AMOUNT");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Map<String, Object>> handleNumberFormat(NumberFormatException e) {
        log.warn("金额格式错误: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_AMOUNT_FORMAT");
        error.put("message", "金额格式不正确，请输入有效的数字");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(NullPointerException e) {
        log.warn("必填参数缺失: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "MISSING_REQUIRED_FIELD");
        error.put("message", "必填字段缺失，请检查请求体");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("操作失败: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        String message = e.getMessage();
        String code = "OPERATION_FAILED";

        if (message != null) {
            if (message.contains("余额不足")) {
                code = "INSUFFICIENT_BALANCE";
            } else if (message.contains("已冻结")) {
                code = "ACCOUNT_FROZEN";
            } else if (message.contains("已关闭")) {
                code = "ACCOUNT_CLOSED";
            }
        }

        error.put("code", code);
        error.put("message", message);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    // === 内部方法 ===

    private Map<String, Object> toTransactionMap(AccountTransaction tx) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("txNo", tx.getTxNo());
        map.put("accountId", tx.getAccountId());
        map.put("operationType", tx.getOperationType().name());
        map.put("direction", tx.getDirection().name());
        map.put("amount", tx.getAmount());
        map.put("balanceBefore", tx.getBalanceBefore());
        map.put("balanceAfter", tx.getBalanceAfter());
        map.put("reference", tx.getReference());
        map.put("createdAt", tx.getCreatedAt());
        return map;
    }

    // === DTO ===

    public static class DepositRequest {
        /** 充值金额（字符串形式，避免精度丢失） */
        private String amount;
        /** 关联订单号（可为空） */
        private String orderId;

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
    }

    public static class WithdrawRequest {
        /** 提现金额 */
        private String amount;

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
    }

    public static class FreezeRequest {
        /** 冻结金额 */
        private String amount;
        /** 冻结原因 */
        private String reason;

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class UnfreezeRequest {
        /** 解冻金额 */
        private String amount;

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
    }
}
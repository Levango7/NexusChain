package org.nexus.walletsvc.controller;

import org.nexus.sdk.wallet.WalletTier;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.approval.WithdrawalApprovalService;
import org.nexus.walletsvc.custody.CustodyService;
import org.nexus.walletsvc.whitelist.AddressWhitelistService;
import org.nexus.walletsvc.whitelist.WhitelistEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 钱包管理服务控制器。
 *
 * <p>承载原 exchange-wallet 的 wallet/ 子包对外端点，包括：
 * <ul>
 *   <li>{@code GET /api/v1/wallet/health}：钱包服务健康检查</li>
 *   <li>{@code GET /api/v1/wallet/whitelist/check}：地址白名单查询</li>
 *   <li>{@code POST /api/v1/wallet/whitelist/add}：加入白名单</li>
 *   <li>{@code POST /api/v1/wallet/whitelist/remove}：移出白名单</li>
 *   <li>{@code POST /api/v1/wallet/withdrawal/request}：发起提现申请</li>
 *   <li>{@code POST /api/v1/wallet/withdrawal/approve}：审批提现</li>
 *   <li>{@code POST /api/v1/wallet/withdrawal/reject}：拒绝提现</li>
 *   <li>{@code POST /api/v1/wallet/withdrawal/execute}：执行已审批提现</li>
 *   <li>{@code GET /api/v1/wallet/custody/balance}：托管余额查询</li>
 *   <li>{@code POST /api/v1/wallet/custody/rebalance}：触发再平衡</li>
 * </ul></p>
 *
 * <p>迁移历史：原 exchange-wallet 的钱包管理端点分散在 NodeController（链节点 RPC）
 * 与 WalletController（钱包工具）中。Phase 2 微服务化后统一收敛到本控制器。
 * NodeController（链节点 RPC）已迁入 signing-service（签名广播必需）。</p>
 */
@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    @Autowired
    private WithdrawalApprovalService withdrawalApprovalService;

    @Autowired
    private CustodyService custodyService;

    @Autowired
    private AddressWhitelistService addressWhitelistService;

    /**
     * 钱包管理服务健康检查端点。
     *
     * @return 服务状态信息
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("service", "nexus-wallet-service");
        status.put("status", "UP");
        return status;
    }

    // === 地址白名单 ===

    /**
     * 地址白名单查询端点。
     *
     * @param address 钱包地址
     * @return 是否加白 + 是否处于首次提币延迟期
     */
    @GetMapping("/whitelist/check")
    public Map<String, Object> checkWhitelist(@RequestParam("address") String address) {
        Map<String, Object> result = new HashMap<>();
        result.put("address", address);
        result.put("whitelisted", addressWhitelistService.isWhitelisted(address));
        result.put("firstTimeDelay", addressWhitelistService.checkFirstTimeWithdrawal(address));
        return result;
    }

    /**
     * 加入白名单端点。
     *
     * @param address    钱包地址
     * @param label      地址标签
     * @param merchantId 商户 ID
     * @return 创建的白名单条目
     */
    @PostMapping("/whitelist/add")
    public WhitelistEntry addWhitelist(@RequestParam("address") String address,
                                       @RequestParam(value = "label", required = false) String label,
                                       @RequestParam("merchantId") String merchantId) {
        return addressWhitelistService.addWhitelist(address, label, merchantId);
    }

    /**
     * 移出白名单端点。
     *
     * @param address 钱包地址
     * @return 操作结果
     */
    @PostMapping("/whitelist/remove")
    public Map<String, Object> removeWhitelist(@RequestParam("address") String address) {
        addressWhitelistService.removeWhitelist(address);
        Map<String, Object> result = new HashMap<>();
        result.put("address", address);
        result.put("removed", true);
        return result;
    }

    // === 提现审批 ===

    /**
     * 发起提现申请端点。
     *
     * @param to       目标钱包地址
     * @param amount   提现金额
     * @param currency 币种
     * @return 提现申请
     */
    @PostMapping("/withdrawal/request")
    public WithdrawalRequest requestWithdrawal(@RequestParam("to") String to,
                                               @RequestParam("amount") BigDecimal amount,
                                               @RequestParam("currency") String currency) {
        return withdrawalApprovalService.requestWithdrawal(to, amount, currency);
    }

    /**
     * 审批提现端点。
     *
     * @param approvalId 提现申请 ID
     * @param approverId 审批人 ID
     * @return 更新后的提现申请
     */
    @PostMapping("/withdrawal/approve")
    public WithdrawalRequest approveWithdrawal(@RequestParam("approvalId") String approvalId,
                                               @RequestParam("approverId") String approverId) {
        return withdrawalApprovalService.approve(approvalId, approverId);
    }

    /**
     * 拒绝提现端点。
     *
     * @param approvalId 提现申请 ID
     * @param approverId 审批人 ID
     * @param reason     拒绝原因
     * @return 更新后的提现申请
     */
    @PostMapping("/withdrawal/reject")
    public WithdrawalRequest rejectWithdrawal(@RequestParam("approvalId") String approvalId,
                                              @RequestParam("approverId") String approverId,
                                              @RequestParam(value = "reason", required = false) String reason) {
        return withdrawalApprovalService.reject(approvalId, approverId, reason);
    }

    /**
     * 执行已审批提现端点。
     *
     * @param approvalId 提现申请 ID
     * @return 更新后的提现申请（EXECUTED 或 FAILED）
     */
    @PostMapping("/withdrawal/execute")
    public WithdrawalRequest executeWithdrawal(@RequestParam("approvalId") String approvalId) {
        return withdrawalApprovalService.executeApprovedWithdrawal(approvalId);
    }

    // === 钱包托管 ===

    /**
     * 托管余额查询端点。
     *
     * @return 热钱包 / 冷钱包余额
     */
    @GetMapping("/custody/balance")
    public Map<String, Object> custodyBalance() {
        Map<String, Object> result = new HashMap<>();
        result.put("hot", custodyService.getHotBalance());
        result.put("cold", custodyService.getColdBalance());
        return result;
    }

    /**
     * 触发再平衡端点。
     *
     * @param target 目标层级（HOT / WARM / COLD）
     * @return 操作结果
     */
    @PostMapping("/custody/rebalance")
    public Map<String, Object> rebalance(@RequestParam("target") WalletTier target) {
        custodyService.rebalance(target);
        Map<String, Object> result = new HashMap<>();
        result.put("target", target);
        result.put("hot", custodyService.getHotBalance());
        result.put("cold", custodyService.getColdBalance());
        return result;
    }
}

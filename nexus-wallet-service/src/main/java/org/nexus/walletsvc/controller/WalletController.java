package org.nexus.walletsvc.controller;

import org.nexus.sdk.wallet.WalletTier;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.approval.WithdrawalApprovalService;
import org.nexus.walletsvc.config.SecurityRoles;
import org.nexus.walletsvc.custody.CustodyService;
import org.nexus.walletsvc.whitelist.AddressWhitelistService;
import org.nexus.walletsvc.whitelist.WhitelistEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
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
     * <p>SECURITY (P0-3): 端点强制 {@code ROLE_OPERATOR} 鉴权。
     * 白名单查询属于运维只读操作，不应公开访问。</p>
     *
     * @param address 钱包地址
     * @return 是否加白 + 是否处于首次提币延迟期
     */
    @PreAuthorize("hasRole('" + SecurityRoles.OPERATOR + "')")
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
     * <p>SECURITY (P0-3): 端点强制 {@code ROLE_ADMIN} 鉴权。
     * 白名单管理（增删）属于高权限管理操作，仅 ADMIN 角色可执行，
     * 防止运维人员误操作或被钓鱼后向白名单注入恶意地址。</p>
     *
     * @param address    钱包地址
     * @param label      地址标签
     * @param merchantId 商户 ID
     * @return 创建的白名单条目
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
    @PostMapping("/whitelist/add")
    public WhitelistEntry addWhitelist(@RequestParam("address") String address,
                                       @RequestParam(value = "label", required = false) String label,
                                       @RequestParam("merchantId") String merchantId) {
        return addressWhitelistService.addWhitelist(address, label, merchantId);
    }

    /**
     * 移出白名单端点。
     *
     * <p>SECURITY (P0-3): 端点强制 {@code ROLE_ADMIN} 鉴权。
     * 白名单管理（增删）属于高权限管理操作，仅 ADMIN 角色可执行。</p>
     *
     * @param address 钱包地址
     * @return 操作结果
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
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
     * <p>SECURITY (P0-3): 端点强制 {@code ROLE_OPERATOR} 鉴权。
     * 提现申请属于运维操作，需认证后执行。</p>
     *
     * @param to       目标钱包地址
     * @param amount   提现金额
     * @param currency 币种
     * @return 提现申请
     */
    @PreAuthorize("hasRole('" + SecurityRoles.OPERATOR + "')")
    @PostMapping("/withdrawal/request")
    public WithdrawalRequest requestWithdrawal(@RequestParam("to") String to,
                                               @RequestParam("amount") BigDecimal amount,
                                               @RequestParam("currency") String currency) {
        return withdrawalApprovalService.requestWithdrawal(to, amount, currency);
    }

    /**
     * 审批提现端点。
     *
     * <p>SECURITY (P0-3): 端点强制 {@code ROLE_APPROVER} 鉴权，
     * 且 {@code approverId} 不再从请求参数获取（避免审批人自报身份的安全风险），
     * 改从 {@link SecurityContextHolder} 认证上下文获取 JWT subject 作为审批人 ID。
     * 这样审批人身份由网关签发的 JWT 强制保证，无法被调用方伪造。</p>
     *
     * @param approvalId 提现申请 ID
     * @return 更新后的提现申请
     */
    @PreAuthorize("hasRole('" + SecurityRoles.APPROVER + "')")
    @PostMapping("/withdrawal/approve")
    public WithdrawalRequest approveWithdrawal(@RequestParam("approvalId") String approvalId) {
        // P0-3：approverId 从认证上下文获取，而非请求参数，避免审批人自报身份
        String approverId = SecurityContextHolder.getContext().getAuthentication().getName();
        return withdrawalApprovalService.approve(approvalId, approverId);
    }

    /**
     * 拒绝提现端点。
     *
     * <p>SECURITY (P0-3): 端点强制 {@code ROLE_APPROVER} 鉴权，
     * 且 {@code approverId} 从 {@link SecurityContextHolder} 认证上下文获取
     * （与 {@link #approveWithdrawal} 一致，避免审批人自报身份）。</p>
     *
     * @param approvalId 提现申请 ID
     * @param reason     拒绝原因
     * @return 更新后的提现申请
     */
    @PreAuthorize("hasRole('" + SecurityRoles.APPROVER + "')")
    @PostMapping("/withdrawal/reject")
    public WithdrawalRequest rejectWithdrawal(@RequestParam("approvalId") String approvalId,
                                              @RequestParam(value = "reason", required = false) String reason) {
        // P0-3：approverId 从认证上下文获取，而非请求参数，避免审批人自报身份
        String approverId = SecurityContextHolder.getContext().getAuthentication().getName();
        return withdrawalApprovalService.reject(approvalId, approverId, reason);
    }

    /**
     * 执行已审批提现端点。
     *
     * <p>SECURITY (P0-3): 端点强制 {@code ROLE_OPERATOR} 鉴权。
     * 提现执行属于运维操作，需认证后执行。</p>
     *
     * @param approvalId 提现申请 ID
     * @return 更新后的提现申请（EXECUTED 或 FAILED）
     */
    @PreAuthorize("hasRole('" + SecurityRoles.OPERATOR + "')")
    @PostMapping("/withdrawal/execute")
    public WithdrawalRequest executeWithdrawal(@RequestParam("approvalId") String approvalId) {
        return withdrawalApprovalService.executeApprovedWithdrawal(approvalId);
    }

    // === 钱包托管 ===

    /**
     * 托管余额查询端点。
     *
     * <p>SECURITY (P0-3): 端点强制 {@code ROLE_OPERATOR} 鉴权。
     * 托管余额属于运维只读信息，需认证后查询。</p>
     *
     * @return 热钱包 / 冷钱包余额
     */
    @PreAuthorize("hasRole('" + SecurityRoles.OPERATOR + "')")
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
     * <p>SECURITY (P0-3): 端点强制 {@code ROLE_ADMIN} 鉴权。
     * 托管再平衡属于高权限管理操作（涉及冷热钱包资金调拨），
     * 仅 ADMIN 角色可执行，防止运维人员误触发资金迁移。</p>
     *
     * @param target 目标层级（HOT / WARM / COLD）
     * @return 操作结果
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
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

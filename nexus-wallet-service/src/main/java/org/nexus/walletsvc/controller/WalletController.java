package org.nexus.walletsvc.controller;

import org.nexus.walletsvc.approval.WithdrawalApprovalService;
import org.nexus.walletsvc.custody.CustodyService;
import org.nexus.walletsvc.whitelist.AddressWhitelistService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 钱包管理服务控制器（骨架）。
 *
 * <p>定义钱包管理服务对外暴露的 REST 端点边界。原实现位于
 * {@code org.nexus.wallet.wallet.controller.NodeController} 与
 * {@code org.nexus.wallet.signing.controller.WalletController}（exchange-wallet）。</p>
 *
 * <p>PoC 阶段：仅暴露服务健康检查与边界信息端点，实际钱包管理逻辑
 * 仍由 exchange-wallet 进程内提供。完整迁移后本控制器将承载：
 * <ul>
 *   <li>{@code GET /api/v1/wallet/health}：钱包服务健康检查</li>
 *   <li>{@code GET /api/v1/wallet/whitelist}：地址白名单查询</li>
 *   <li>{@code POST /api/v1/wallet/withdrawal}：发起提现申请</li>
 *   <li>{@code POST /api/v1/wallet/withdrawal/approve}：审批提现</li>
 *   <li>{@code GET /api/v1/wallet/custody/{walletId}}：托管层级查询</li>
 * </ul></p>
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

    /**
     * 地址白名单查询端点（骨架）。
     *
     * @param address 钱包地址
     * @return 是否加白
     */
    @GetMapping("/whitelist/check")
    public Map<String, Object> checkWhitelist(@RequestParam("address") String address) {
        Map<String, Object> result = new HashMap<>();
        result.put("address", address);
        result.put("whitelisted", addressWhitelistService.isWhitelisted(address));
        return result;
    }

    /**
     * 托管层级查询端点（骨架）。
     *
     * @param walletId 钱包 ID
     * @return 托管信息
     */
    @GetMapping("/custody")
    public Map<String, Object> custody(@RequestParam("walletId") String walletId) {
        Map<String, Object> result = new HashMap<>();
        result.put("walletId", walletId);
        result.put("coldCustody", custodyService.isColdCustody(walletId));
        result.put("tier", custodyService.getCustodyTier(walletId));
        return result;
    }
}
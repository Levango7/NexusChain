package org.nexus.gateway.fundreport;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 商户出款账户（结算账户）管理 REST API（P0 修复 2026-10-11）。
 *
 * <p><b>为什么必须补这个入口</b>：v2.61.0 建了出款账户模型、v2.62.0 把"无生效账户就拒绝扣账"
 * 的闸门接进了自动提现——但 {@link MerchantPayoutAccountService} 的
 * {@code bind}/{@code activate}/{@code disable} **没有任何调用方、也没有控制器**：
 * 商户**无法绑定出款账户** ⇒ {@code resolveActiveAccount} 恒为空 ⇒ **自动提现永远不执行**
 * （闸门正确地拒绝了一切，却也拒绝了这个功能本身）。本控制器把入口补上。</p>
 *
 * <p><b>权限</b>：绑定/核验/停用**一律 ADMIN**。出款去向是资金出口配置，属最敏感的数据面；
 * 且当前实现的 {@code MerchantOwnershipGuard} 未覆盖到本模块——若开放给 MERCHANT 自助绑定，
 * 必须同时按商户主体做归属校验（后者为后续项，见类尾说明）。</p>
 *
 * <p><b>流程</b>：绑定 → {@code PENDING_VERIFY}（不可用于出款）→ 运营核验<b>户名与商户主体一致
 * + 账户归属真实</b>（线下）→ {@code activate} → 方可用于出款。</p>
 */
@RestController
@RequestMapping("/api/v1/payout-accounts")
@PreAuthorize("hasRole('ADMIN')")
public class PayoutAccountController {

    private final MerchantPayoutAccountService service;

    public PayoutAccountController(MerchantPayoutAccountService service) {
        this.service = service;
    }

    /**
     * 绑定出款账户（进入 PENDING_VERIFY，需核验后才可用于出款）。
     *
     * <p>请求体字段：{@code merchantId} / {@code accountType}（BANK_CARD/ALIPAY/PLATFORM_SETTLEMENT）/
     * {@code accountNo} / {@code accountName} / {@code complianceEntity}（仅 PLATFORM_SETTLEMENT 需要）。</p>
     *
     * <p>合规护栏由服务层拒绝：平台结算账户必须持牌主体（二清红线）、户名与商户主体必须一致。</p>
     */
    @PostMapping
    public ResponseEntity<?> bind(@RequestBody Map<String, Object> body) {
        try {
            Long merchantId = Long.valueOf(String.valueOf(body.get("merchantId")));
            MerchantPayoutAccount.AccountType type = MerchantPayoutAccount.AccountType.valueOf(
                    String.valueOf(body.get("accountType")).trim().toUpperCase());
            String accountNo = String.valueOf(body.get("accountNo"));
            String accountName = String.valueOf(body.get("accountName"));
            boolean complianceEntity = Boolean.parseBoolean(
                    String.valueOf(body.getOrDefault("complianceEntity", "false")));

            MerchantPayoutAccount saved =
                    service.bind(merchantId, type, accountNo, accountName, complianceEntity);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            // 参数错 / 违反合规护栏（二清红线、户名不一致）→ 400 并原样返回原因
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 查询某商户的出款账户（含待核验/已停用，便于运营核对）。 */
    @GetMapping("/{merchantId}")
    public ResponseEntity<List<MerchantPayoutAccount>> list(@PathVariable Long merchantId) {
        return ResponseEntity.ok(service.list(merchantId));
    }

    /** 核验通过 → 生效（此后方可作为出款去向）。 */
    @PostMapping("/{accountId}/activate")
    public ResponseEntity<?> activate(@PathVariable Long accountId) {
        try {
            return ResponseEntity.ok(service.activate(accountId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 停用（不再作为出款去向；留痕，不物理删除）。 */
    @PostMapping("/{accountId}/disable")
    public ResponseEntity<?> disable(@PathVariable Long accountId) {
        try {
            return ResponseEntity.ok(service.disable(accountId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}

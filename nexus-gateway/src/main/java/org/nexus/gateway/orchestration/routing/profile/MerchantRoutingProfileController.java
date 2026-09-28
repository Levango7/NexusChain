package org.nexus.gateway.orchestration.routing.profile;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商户路由画像 REST API（Wave 16 模块六）。
 *
 * <p>商户归属校验（P0 IDOR 防护沿用 Wave 15 模式）：商户只能创建/查看/修改
 * 自己（merchantId = 认证上下文）的画像；行业级配置（merchantId=null）
 * 只读，不接受商户写入。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/v1/routing/profiles} — 当前商户的画像配置</li>
 *   <li>{@code POST /api/v1/routing/profiles} — 创建（merchantId 必须 = 认证商户）</li>
 *   <li>{@code PUT /api/v1/routing/profiles/{id}} — 更新（归属校验）</li>
 *   <li>{@code DELETE /api/v1/routing/profiles/{id}} — 删除（归属校验）</li>
 *   <li>{@code GET /api/v1/routing/profiles/resolve?amount=123&industry=...} — 解析画像（归属校验）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/routing/profiles")
public class MerchantRoutingProfileController {

    private static final Logger log = LoggerFactory.getLogger(MerchantRoutingProfileController.class);

    private final MerchantRoutingProfileService service;
    private final MerchantOwnershipGuard ownershipGuard;

    public MerchantRoutingProfileController(MerchantRoutingProfileService service,
                                            MerchantOwnershipGuard ownershipGuard) {
        this.service = service;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping
    public ResponseEntity<List<MerchantRoutingProfile>> listMine(HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        return ResponseEntity.ok(service.listByMerchant(callerMerchantId));
    }

    @PostMapping
    public ResponseEntity<MerchantRoutingProfile> create(@RequestBody MerchantRoutingProfile profile,
                                                         HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        if (profile.getMerchantId() == null || !callerMerchantId.equals(profile.getMerchantId())) {
            // 商户只能创建自己的画像；行业级画像（merchantId=null）不接受商户写入
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(service.create(profile));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<MerchantRoutingProfile> update(@PathVariable Long id,
                                                         @RequestBody MerchantRoutingProfile update,
                                                         HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        MerchantRoutingProfile existing = service.get(id);
        ownershipGuard.requireOwned(callerMerchantId, existing.getMerchantId(), "routing-profile", id);
        try {
            return ResponseEntity.ok(service.update(id, update));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        MerchantRoutingProfile existing = service.get(id);
        ownershipGuard.requireOwned(callerMerchantId, existing.getMerchantId(), "routing-profile", id);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/resolve")
    public ResponseEntity<ResolvedRoutingProfile> resolve(
            @RequestParam(required = false) BigDecimal amount,
            @RequestParam(required = false) String industry,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        return ResponseEntity.ok(service.resolve(callerMerchantId, industry, amount));
    }
}

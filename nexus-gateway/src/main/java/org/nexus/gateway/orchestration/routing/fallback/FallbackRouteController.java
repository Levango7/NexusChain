package org.nexus.gateway.orchestration.routing.fallback;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 降级路由配置 REST API（Wave 16 模块三）。
 *
 * <p>商户级配置走归属校验（P0 IDOR 防护沿用 Wave 15 模式）：商户只能创建/
 * 查看/修改自己（merchantId = 认证上下文）的配置；全局配置（merchantId=null）
 * 只读，不接受商户写入。</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/v1/routing/fallback-configs} — 当前商户的配置 + 全局配置</li>
 *   <li>{@code POST /api/v1/routing/fallback-configs} — 创建（merchantId 必须 = 认证商户）</li>
 *   <li>{@code PUT /api/v1/routing/fallback-configs/{id}} — 更新（归属校验）</li>
 *   <li>{@code DELETE /api/v1/routing/fallback-configs/{id}} — 删除（归属校验）</li>
 *   <li>{@code GET /api/v1/routing/fallback-configs/resolve} — 解析降级链（归属校验）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/routing/fallback-configs")
public class FallbackRouteController {

    private static final Logger log = LoggerFactory.getLogger(FallbackRouteController.class);

    private final FallbackRouteService service;
    private final MerchantOwnershipGuard ownershipGuard;

    public FallbackRouteController(FallbackRouteService service, MerchantOwnershipGuard ownershipGuard) {
        this.service = service;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping
    public ResponseEntity<List<FallbackRouteConfig>> listMine(HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        return ResponseEntity.ok(service.listByMerchant(callerMerchantId));
    }

    @PostMapping
    public ResponseEntity<FallbackRouteConfig> create(@RequestBody FallbackRouteConfig config,
                                                      HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        if (config.getMerchantId() == null || !callerMerchantId.equals(config.getMerchantId())) {
            // 商户只能创建自己的配置；全局配置（merchantId=null）不接受商户写入
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(service.create(config));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<FallbackRouteConfig> update(@PathVariable Long id,
                                                      @RequestBody FallbackRouteConfig update,
                                                      HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        FallbackRouteConfig existing = service.get(id);
        ownershipGuard.requireOwned(callerMerchantId, existing.getMerchantId(), "fallback-config", id);
        try {
            return ResponseEntity.ok(service.update(id, update));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        FallbackRouteConfig existing = service.get(id);
        ownershipGuard.requireOwned(callerMerchantId, existing.getMerchantId(), "fallback-config", id);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/resolve")
    public ResponseEntity<List<String>> resolve(@RequestParam String primaryConnector,
                                                @RequestParam(required = false) BigDecimal amount,
                                                @RequestParam(required = false) String currency,
                                                HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        return ResponseEntity.ok(service.resolveFallbackChain(callerMerchantId, primaryConnector, amount, currency));
    }
}

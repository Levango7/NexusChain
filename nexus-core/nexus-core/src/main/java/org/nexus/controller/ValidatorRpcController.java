package org.nexus.controller;

import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 验证者集管理 RPC（NexFinality 治理→验证者集连接轴的 core 端）。
 *
 * <p>由 oracle 模块的 {@code ValidatorSetPort} HTTP 客户端调用，
 * 桥接 {@link ValidatorRegistry} 完成新增/移除验证人。URL 前缀 {@code /rpc/v1} 与
 * {@link PaymentRpcController} 一致，便于 gateway/oracle 使用统一客户端。</p>
 */
@RestController
@RequestMapping("/rpc/v1/validators")
public class ValidatorRpcController {

    private static final Logger log = LoggerFactory.getLogger(ValidatorRpcController.class);

    @Autowired
    private ValidatorRegistry validatorRegistry;

    /**
     * POST /rpc/v1/validators/register
     * 注册（或重新激活）验证人。
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterValidatorRequest req) {
        try {
            if (req == null || req.address() == null || req.publicKey() == null) {
                return result(4001, "address and publicKey are required", null);
            }
            if (req.stakeAmount() == null) {
                return result(4001, "stakeAmount is required", null);
            }
            boolean ok = validatorRegistry.register(
                    req.address(), req.publicKey(),
                    new BigDecimal(req.stakeAmount()), req.commissionRate() == null ? 0.1 : req.commissionRate());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("address", req.address());
            data.put("registered", ok);
            return ok
                    ? result(2000, "validator registered", data)
                    : result(4002, "register failed (invalid stake / full set / duplicate)", data);
        } catch (Exception e) {
            log.error("Validator register failed: address={}, error={}",
                    req == null ? null : req.address(), e.getMessage(), e);
            return result(5001, "register error: " + e.getMessage(), null);
        }
    }

    /**
     * POST /rpc/v1/validators/unregister
     * 注销验证人（状态置 INACTIVE）。
     */
    @PostMapping("/unregister")
    public Map<String, Object> unregister(@RequestBody UnregisterValidatorRequest req) {
        try {
            if (req == null || req.address() == null) {
                return result(4001, "address is required", null);
            }
            boolean ok = validatorRegistry.unregister(req.address());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("address", req.address());
            data.put("unregistered", ok);
            return ok
                    ? result(2000, "validator unregistered", data)
                    : result(4002, "validator not found", data);
        } catch (Exception e) {
            log.error("Validator unregister failed: address={}, error={}",
                    req == null ? null : req.address(), e.getMessage(), e);
            return result(5001, "unregister error: " + e.getMessage(), null);
        }
    }

    /**
     * GET /rpc/v1/validators/status
     * 查询指定验证人状态。
     */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String address) {
        Validator v = validatorRegistry.getValidator(address);
        Map<String, Object> data = new LinkedHashMap<>();
        if (v == null) {
            data.put("address", address);
            data.put("exists", false);
            return result(2000, "validator not found", data);
        }
        data.put("address", v.getAddress());
        data.put("exists", true);
        data.put("status", v.getStatus().name());
        data.put("stake_amount", v.getStakeAmount().toPlainString());
        data.put("commission_rate", v.getCommissionRate());
        return result(2000, "success", data);
    }

    // === 请求 DTO ===

    public record RegisterValidatorRequest(String address, String publicKey,
                                           String stakeAmount, Double commissionRate) {}
    public record UnregisterValidatorRequest(String address) {}

    private Map<String, Object> result(int code, String message, Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("message", message);
        r.put("data", data);
        return r;
    }
}
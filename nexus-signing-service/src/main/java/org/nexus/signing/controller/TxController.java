package org.nexus.signing.controller;

import org.nexus.signing.keystore.PlatformKeystore;
import org.nexus.signing.mpc.MpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 签名服务交易控制器（骨架）。
 *
 * <p>定义签名服务对外暴露的 REST 端点边界。原实现位于
 * {@code org.nexus.wallet.signing.controller.TxController}（exchange-wallet），
 * 提供 {@code /ClientToTransferAccount} 与 {@code /api/v1/transfers/sign} 等端点。</p>
 *
 * <p>PoC 阶段：仅暴露服务健康检查与边界信息端点，实际签名 + 广播逻辑
 * 仍由 exchange-wallet 进程内提供。完整迁移后本控制器将承载：
 * <ul>
 *   <li>{@code POST /api/v1/transfers/sign}：使用平台密钥库签名并广播</li>
 *   <li>{@code POST /api/v1/mpc/sign}：MPC 阈值签名</li>
 *   <li>{@code GET /api/v1/signing/health}：签名服务健康检查</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/v1/signing")
public class TxController {

    @Autowired
    private PlatformKeystore platformKeystore;

    @Autowired
    private MpcService mpcService;

    /**
     * 签名服务健康检查端点。
     *
     * @return 服务状态信息
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("service", "nexus-signing-service");
        status.put("keystoreLoaded", platformKeystore.isLoaded());
        status.put("mpcThreshold", mpcService.getThreshold());
        status.put("mpcTotal", mpcService.getTotalParticipants());
        return status;
    }

    /**
     * 签名能力查询端点（骨架）。
     *
     * @param amount 提现金额
     * @return MPC 签名能力
     */
    @GetMapping("/capability")
    public Map<String, Object> capability(BigDecimal amount) {
        Map<String, Object> result = new HashMap<>();
        result.put("amount", amount);
        result.put("canSign", mpcService.canSign(amount));
        return result;
    }
}
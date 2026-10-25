package org.nexus.bridge.messaging;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桥 messaging 装配观测端点（PLAN-037 切片 3）。
 *
 * <p>{@code GET /api/v1/bridge/messaging}：返回装配快照
 * （{@link BridgeMessagingStatus} 的 JSON 形态）。装配关闭（默认）时返回
 * {@code {"relayEnabled":false, "reason":...}}——端点恒可用，运维无需区分
 * "未装配"与"404"，一处即可回答"插座通没通电"。</p>
 *
 * <p>安全：只读 GET，随既有白名单模式在 {@code SecurityConfig} 放行
 * （与 {@code /api/v1/bridge/status} 同口径）。</p>
 *
 * @since 2.76.0
 */
@RestController
@RequestMapping("/api/v1/bridge")
public class BridgeMessagingController {

    private final ObjectProvider<BridgeMessagingStatus> statusProvider;

    public BridgeMessagingController(ObjectProvider<BridgeMessagingStatus> statusProvider) {
        this.statusProvider = statusProvider;
    }

    /**
     * 装配快照：开关 / 签数阈值 / 超时 / store 实现 / 已装配适配器数。
     */
    @GetMapping("/messaging")
    public ResponseEntity<Map<String, Object>> messaging() {
        BridgeMessagingStatus status = statusProvider.getIfAvailable();
        Map<String, Object> body = new LinkedHashMap<>();
        if (status == null) {
            body.put("relayEnabled", false);
            body.put("reason", "nexus.bridge.messaging.relay-enabled is not true");
            return ResponseEntity.ok(body);
        }
        body.put("relayEnabled", status.relayEnabled());
        body.put("requiredSignatures", status.requiredSignatures());
        body.put("messageTimeout", status.messageTimeout());
        body.put("storeType", status.storeType());
        body.put("targetAdapters", status.targetAdapters());
        return ResponseEntity.ok(body);
    }
}

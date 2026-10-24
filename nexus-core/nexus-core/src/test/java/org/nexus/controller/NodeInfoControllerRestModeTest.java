package org.nexus.controller;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rest 形态的 NodeInfoController 降级回归 —— F2 rest 验证发现（2026-10-25）。
 *
 * <p>旧实现 {@code @Autowired private PeerServer} 为必填注入，rest 模式
 * （无 PeerServer bean）直接导致上下文启动失败；修复为可选注入 +
 * {@code /peers/status} null 降级。本测试锁定 null 时不再 NPE、返回空对端。</p>
 */
class NodeInfoControllerRestModeTest {

    @Test
    @SuppressWarnings("unchecked")
    void peersStatusDegradesWhenNoPeerServer() {
        NodeInfoController controller = new NodeInfoController();
        // rest 形态：peerServer 未注入（字段保持 null）——旧实现在此直接 NPE/启动失败
        Object res = controller.getP2P();
        assertNotNull(res);
        assertTrue(res instanceof APIResult);
        Map<String, Object> data = (Map<String, Object>) ((APIResult<Object>) res).getData();
        assertEquals(Collections.emptyList(), data.get("peers"));
        assertEquals(Collections.emptyList(), data.get("bootstraps"));
        assertEquals(null, data.get("self"));
    }
}

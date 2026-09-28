package org.nexus.signing.mpc.cggmp;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.signing.mpc.crypto.MpcEngineRouter;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MpcCggmpClusterConfig} 单元测试（P0-1 覆盖率补线）。
 *
 * <p>覆盖装配期 fail-closed 语义：cggmp-enabled=true 时集群不完整
 * （端点数 &lt;2 / channel 缺失 / channel 已关闭）必须启动失败；
 * false 时返回 null（CGGMP 路径空闲，不影响 GG0 默认路径）。</p>
 */
class MpcCggmpClusterConfigTest {

    private MpcEngineRouter router;

    @BeforeEach
    void setUp() {
        router = mock(MpcEngineRouter.class);
    }

    private MpcCggmpClusterConfig config(boolean enabled, long deadlineMs) {
        MpcCggmpClusterConfig cfg = new MpcCggmpClusterConfig(router);
        ReflectionTestUtils.setField(cfg, "cggmpEnabled", enabled);
        ReflectionTestUtils.setField(cfg, "cggmpDeadlineMs", deadlineMs);
        return cfg;
    }

    /** 装配 n 个健康端点：channel 未关闭 + stub 就绪。 */
    private void stubHealthyEndpoints(int n) {
        when(router.getEndpointCount()).thenReturn(n);
        for (int i = 0; i < n; i++) {
            ManagedChannel ch = mock(ManagedChannel.class);
            when(ch.isShutdown()).thenReturn(false);
            when(router.getChannel(i)).thenReturn(ch);
            when(router.newBlockingStub(i))
                    .thenReturn(mock(MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub.class));
        }
    }

    @Test
    @DisplayName("构造：router 为 null → NPE（fail-fast）")
    void rejectsNullRouter() {
        assertThrows(NullPointerException.class, () -> new MpcCggmpClusterConfig(null));
    }

    @Test
    @DisplayName("cggmp-enabled=true + 0 端点 → 启动失败（拒绝单进程路径）")
    void failClosedWhenNoEndpoints() {
        when(router.getEndpointCount()).thenReturn(0);
        MpcCggmpClusterConfig cfg = config(true, 120_000L);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, cfg::cggmpClusterSessionDriver);
        assertTrue(ex.getMessage().contains(">=2 endpoints"), ex.getMessage());
    }

    @Test
    @DisplayName("cggmp-enabled=true + 1 端点 → 启动失败（门限至少 2 方）")
    void failClosedWhenSingleEndpoint() {
        stubHealthyEndpoints(1);
        MpcCggmpClusterConfig cfg = config(true, 120_000L);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, cfg::cggmpClusterSessionDriver);
        assertTrue(ex.getMessage().contains("< 2"), ex.getMessage());
    }

    @Test
    @DisplayName("cggmp-enabled=true + 某端 channel 为 null → 启动失败（不静默降级子集）")
    void failClosedWhenChannelNull() {
        when(router.getEndpointCount()).thenReturn(3);
        for (int i : new int[] {0, 2}) {
            ManagedChannel ch = mock(ManagedChannel.class);
            when(ch.isShutdown()).thenReturn(false);
            when(router.getChannel(i)).thenReturn(ch);
            when(router.newBlockingStub(i))
                    .thenReturn(mock(MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub.class));
        }
        when(router.getChannel(1)).thenReturn(null);
        when(router.getEndpointDescription(1)).thenReturn("ep-1");
        MpcCggmpClusterConfig cfg = config(true, 120_000L);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, cfg::cggmpClusterSessionDriver);
        assertTrue(ex.getMessage().contains("channel[1] unavailable"), ex.getMessage());
    }

    @Test
    @DisplayName("cggmp-enabled=true + 某端 channel 已关闭 → 启动失败")
    void failClosedWhenChannelShutdown() {
        stubHealthyEndpoints(3);
        ManagedChannel shutdownCh = mock(ManagedChannel.class);
        when(shutdownCh.isShutdown()).thenReturn(true);
        when(router.getChannel(2)).thenReturn(shutdownCh);
        MpcCggmpClusterConfig cfg = config(true, 120_000L);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, cfg::cggmpClusterSessionDriver);
        assertTrue(ex.getMessage().contains("channel[2] unavailable"), ex.getMessage());
    }

    @Test
    @DisplayName("cggmp-enabled=false + 集群不完整 → 返回 null（GG20 默认路径不受影响）")
    void idleWhenDisabled() {
        when(router.getEndpointCount()).thenReturn(0);
        MpcCggmpClusterConfig cfg = config(false, 120_000L);

        assertNull(cfg.cggmpClusterSessionDriver());
    }

    @Test
    @DisplayName("cggmp-enabled=false + channel null → 返回 null 不抛错")
    void idleWhenDisabledWithBrokenChannel() {
        when(router.getEndpointCount()).thenReturn(3);
        when(router.getChannel(0)).thenReturn(null);
        when(router.getEndpointDescription(0)).thenReturn("ep-0");
        MpcCggmpClusterConfig cfg = config(false, 120_000L);

        assertNull(cfg.cggmpClusterSessionDriver());
    }

    @Test
    @DisplayName("cggmp-enabled=true + 3 健康端点 → 装配驱动（parties=3）")
    void happyPathBuildsDriver() {
        stubHealthyEndpoints(3);
        MpcCggmpClusterConfig cfg = config(true, 120_000L);

        CggmpClusterSessionDriver driver = cfg.cggmpClusterSessionDriver();

        assertNotNull(driver);
        assertEquals(3, driver.parties());
    }

    @Test
    @DisplayName("deadline<=0 → 回退默认 120s，仍可装配（2 端点）")
    void deadlineFallbackUsedWhenNonPositive() {
        stubHealthyEndpoints(2);
        MpcCggmpClusterConfig cfg = config(true, 0L);

        CggmpClusterSessionDriver driver = cfg.cggmpClusterSessionDriver();

        assertNotNull(driver);
        assertEquals(2, driver.parties());
    }
}

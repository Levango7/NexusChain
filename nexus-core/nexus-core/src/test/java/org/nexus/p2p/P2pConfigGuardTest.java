package org.nexus.p2p;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2P 配置启动校验。
 *
 * <p>覆盖两类静默失效：非法的 {@code p2p.mode} 导致 P2P 组件全不装配；
 * {@code p2p.mode=rest} 与 {@code p2p.tls.enabled=true} 自相矛盾（rest 走明文 HTTP）。</p>
 */
class P2pConfigGuardTest {

    private static P2pConfigGuard guard(String mode, boolean tlsEnabled) {
        P2pConfigGuard guard = new P2pConfigGuard();
        ReflectionTestUtils.setField(guard, "mode", mode);
        ReflectionTestUtils.setField(guard, "tlsEnabled", tlsEnabled);
        return guard;
    }

    @Test
    @DisplayName("grpc / rest 均通过校验")
    void supportedModesPass() {
        assertThatCode(() -> guard("grpc", false).validate()).doesNotThrowAnyException();
        assertThatCode(() -> guard("rest", false).validate()).doesNotThrowAnyException();
        assertThatCode(() -> guard(" GRPC ", false).validate())
                .as("大小写与空白应归一化").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非法 p2p.mode 启动即失败，而不是装配不出任何 P2P 组件")
    void invalidModeFailsFast() {
        assertThatThrownBy(() -> guard("http", false).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("p2p.mode=http 非法");
        assertThatThrownBy(() -> guard("", false).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rest 形态开 TLS 启动即失败，避免误以为链路由 TLS 保护")
    void restWithTlsFailsFast() {
        assertThatThrownBy(() -> guard("rest", true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持 TLS");
    }

    @Test
    @DisplayName("grpc 形态开 TLS 正常")
    void grpcWithTlsPasses() {
        assertThatCode(() -> guard("grpc", true).validate()).doesNotThrowAnyException();
    }
}

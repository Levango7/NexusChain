package org.nexus.gateway.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TccActionResolver} 的 participantId→action 映射单元测试（#31-Step2a）。
 */
class TccActionResolverTest {

    /** 无状态测试参与方：只关心 participantId，三阶段空实现。 */
    private static ReplayableTccAction actionOf(String pid) {
        return new ReplayableTccAction() {
            @Override public boolean tryAction(TransactionContext ctx) { return true; }
            @Override public void confirmAction(TransactionContext ctx) { }
            @Override public void cancelAction(TransactionContext ctx) { }
            @Override public String getParticipantId() { return pid; }
        };
    }

    @Test
    @DisplayName("按 participantId 命中；未知/空 id 返回 empty")
    void resolveHitsByParticipantId() {
        TccActionResolver resolver = new TccActionResolver(List.of(actionOf("payment-confirm")));

        assertTrue(resolver.resolve("payment-confirm").isPresent());
        assertFalse(resolver.resolve("unknown").isPresent());
        assertFalse(resolver.resolve(null).isPresent(), "null participantId 不得抛错，返回 empty");
        assertFalse(resolver.resolve("").isPresent());
        assertEquals(1, resolver.registeredCount());
    }

    @Test
    @DisplayName("participantId 为空的 bean 被忽略，不入表")
    void blankParticipantIdIsSkipped() {
        TccActionResolver resolver = new TccActionResolver(List.of(actionOf(""), actionOf("  "), actionOf("ok")));
        assertEquals(1, resolver.registeredCount(), "只有 'ok' 应被注册");
        assertTrue(resolver.resolve("ok").isPresent());
    }

    @Test
    @DisplayName("空注册表时 resolve 恒 empty（Step1 的 PENDING_MANUAL 兜底据此触发）")
    void emptyResolverAlwaysMisses() {
        TccActionResolver resolver = new TccActionResolver(List.of());
        Optional<TccAction> hit = resolver.resolve("payment-confirm");
        assertFalse(hit.isPresent());
    }
}

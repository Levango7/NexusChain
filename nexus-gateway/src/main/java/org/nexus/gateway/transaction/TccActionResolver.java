package org.nexus.gateway.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCC 参与方重建器 — 在进程内 {@code actionRegistry} miss 时（典型=服务重启后）按
 * {@code participantId} 找回一个<b>无状态、可重放</b>的 {@link ReplayableTccAction}。
 *
 * <p>这是 #102 PLAN §4.1 的落地，但<b>刻意不用反射式 {@code {beanName,methodName,argsJson}} 描述符</b>：
 * 当前唯一的生产 TCC 参与方是无状态 Spring 单例 bean，按 {@code participantId→bean} 映射即可重建，
 * 避免了反射、{@code default typing} 反序列化等更脆的攻击面（PLAN §7 风险表里那两条）。
 * 重建所需的全部业务输入来自 {@code TransactionLog.payload}（由
 * {@link TccTransactionManager} 反序列化成 {@link TransactionContext}）。</p>
 *
 * <p>没有注册任何 {@link ReplayableTccAction} 时，{@link #resolve} 恒空 ⇒ 恢复方法退回
 * {@link TransactionStepStatus#PENDING_MANUAL}（#107 Step1 的行为），因此本器是纯增量的。</p>
 */
@Component
public class TccActionResolver {

    private static final Logger log = LoggerFactory.getLogger(TccActionResolver.class);

    /** participantId → 可重放 action（无状态单例）。 */
    private final Map<String, ReplayableTccAction> byParticipantId = new ConcurrentHashMap<>();

    /**
     * 用 {@link ObjectProvider} 收集容器内所有 {@link ReplayableTccAction} bean。
     *
     * <p>用 ObjectProvider 而非 {@code List<ReplayableTccAction>} 直注：当参与方模块尚未接入时，
     * 空集合也不致因"找不到 bean"阻断 {@link TccTransactionManager} 装配。</p>
     */
    @Autowired
    public TccActionResolver(ObjectProvider<ReplayableTccAction> replayableActions) {
        register(replayableActions.orderedStream().toList());
    }

    /** 测试/手动装配用的便捷构造：直接给一组可重放参与方。 */
    public TccActionResolver(List<ReplayableTccAction> replayableActions) {
        register(replayableActions);
    }

    private void register(List<ReplayableTccAction> actions) {
        for (ReplayableTccAction action : actions) {
            String pid = action.getParticipantId();
            if (pid == null || pid.isBlank()) {
                log.warn("忽略 participantId 为空的 ReplayableTccAction: {}（participantId 必须稳定且非空）",
                        action.getClass().getName());
                continue;
            }
            ReplayableTccAction prev = byParticipantId.putIfAbsent(pid, action);
            if (prev != null && prev != action) {
                log.warn("participantId={} 存在多个 ReplayableTccAction（{} vs {}），恢复时取先注册的",
                        pid, prev.getClass().getName(), action.getClass().getName());
            }
        }
        log.info("TccActionResolver 初始化：注册 {} 个可重放参与方 {}", byParticipantId.size(), byParticipantId.keySet());
    }

    /**
     * 按 participantId 解析可重放 action。
     *
     * @param participantId 来自 {@code TransactionLog.getParticipantId()}
     * @return 命中的无状态 action；未注册则空
     */
    public Optional<TccAction> resolve(String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byParticipantId.get(participantId));
    }

    /** 已注册的参与方数量（供测试/监控）。 */
    public int registeredCount() {
        return byParticipantId.size();
    }
}

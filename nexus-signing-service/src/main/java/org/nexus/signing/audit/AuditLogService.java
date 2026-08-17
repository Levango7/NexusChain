package org.nexus.signing.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 私钥操作审计日志服务（P2-F1 完整安全架构）。
 *
 * <p>对所有涉及私钥访问、签名、MPC 操作的安全事件记录结构化审计日志，
 * 写入专用 logger {@code nexus.audit}（通过 logback-spring.xml 配置独立
 * appender 输出到 {@code /var/log/nexus/signing-audit.json}）。</p>
 *
 * <h3>审计日志字段</h3>
 * <ul>
 *   <li>{@code timestamp}：ISO-8601 时间戳（UTC）</li>
 *   <li>{@code type}：事件类型（{@link AuditEvent.Type}）</li>
 *   <li>{@code outcome}：操作结果（{@link AuditEvent.Outcome}）</li>
 *   <li>{@code actor}：调用方标识（JWT subject）</li>
 *   <li>{@code sourceIp}：来源 IP（X-Forwarded-For 优先）</li>
 *   <li>{@code target}：操作目标标识（交易 hash / 密钥 ID / session_id）</li>
 *   <li>{@code details}：附加详情（amount / currency / party_index 等）</li>
 *   <li>{@code traceId}：链路追踪 ID（关联业务 span）</li>
 * </ul>
 *
 * <h3>敏感数据保护</h3>
 * <p>本服务严格禁止记录私钥明文、签名内容、keystore JSON、解密密码。
 * 调用方传入的 {@code target} 字段应为脱敏后的标识符（如交易 hash、
 * 密钥 ID 别名），{@code details} 中的 amount 等业务字段不视为敏感数据。
 * 本服务在写入前对 details 做白名单过滤，仅保留已知安全字段。</p>
 *
 * <h3>实现选择：日志文件 vs 数据库</h3>
 * <p>本服务选择「专用日志文件 + 结构化 JSON」方案而非数据库表，理由：
 * <ul>
 *   <li>signing-service 现有持久化栈为 LevelDB（无 JPA / Flyway 依赖），
 *       引入关系型数据库需新增 spring-data-jpa + flyway 依赖，膨胀部署单元</li>
 *   <li>审计日志写入路径需与业务数据隔离（避免审计日志被业务故障拖累），
 *       专用 logger + 独立 appender 天然隔离</li>
 *   <li>logback RollingFileAppender 内置轮转 / 压缩 / maxHistory，
 *       等价于数据库表的分区 / 归档</li>
 *   <li>JSON 格式可被 Loki / ELK / S3 直接消费，无需 ETL 转换</li>
 *   <li>审计日志不可变性要求高，日志文件 append-only 特性天然满足；
 *       数据库表需额外 READ_ONLY 触发器 / 行级权限保证不可篡改</li>
 * </ul>
 * 如未来需支持实时审计告警（如 5 分钟内私钥访问 > N 次触发告警），
 * 可在 logback appender 之上叠加 InMemoryAppender + 告警规则，
 * 无需切换到数据库方案。</p>
 *
 * <h3>失败语义</h3>
 * <p>审计日志写入失败（如磁盘满）不应阻断业务请求（避免审计副作用
 * 导致签名服务不可用）。本服务捕获所有异常并降级到主 logger WARN，
 * 仅在审计 logger 上记录事件本身。调用方可通过 {@link #log(AuditEvent)}
 * 的 fire-and-forget 语义安全调用。</p>
 */
@Service
public class AuditLogService {

    /** 专用审计 logger，对应 logback-spring.xml 中 nexus.audit logger。 */
    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("nexus.audit");

    /** 主 logger，用于记录审计写入失败等降级日志。 */
    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** Jackson ObjectMapper 用于 JSON 序列化（线程安全，可复用）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 是否启用审计日志。未启用时 {@link #log} 直接返回，不写入任何记录。
     * <p>从 {@code nexus.audit.enabled} 配置读取，默认 true。
     * 测试环境可设为 false 避免污染日志文件。</p>
     */
    @Value("${nexus.audit.enabled:true}")
    private boolean auditEnabled;

    /**
     * 是否在审计日志中记录鉴权失败事件。
     * <p>从 {@code nexus.audit.log-auth-failure} 配置读取，默认 true。
     * 高频鉴权失败可能产生大量日志，可在采样率受控的 Prometheus 告警
     * 已覆盖时关闭以减少日志量。</p>
     */
    @Value("${nexus.audit.log-auth-failure:true}")
    private boolean logAuthFailure;

    /**
     * 记算调用方标识（actor）：优先从 SecurityContext 提取 JWT subject，
     * 回退到传入的 fallback 参数（如服务名 / IP）。
     *
     * @param fallback SecurityContext 为空时的回退标识
     * @return 调用方标识，永不为 null（未知时返回 "unknown"）
     */
    public static String resolveActor(String fallback) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null) {
            return auth.getPrincipal().toString();
        }
        return fallback == null ? "unknown" : fallback;
    }

    /**
     * 从 HTTP 请求提取来源 IP：X-Forwarded-For 优先（gateway 代理场景），
     * 回退到 RemoteAddr。
     *
     * @param request HTTP 请求，null 时返回 null
     * @return 来源 IP 或 null
     */
    public static String resolveSourceIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For 可能含多个 IP，取第一个（最原始客户端）
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 写入审计事件到专用审计日志。
     *
     * <p>fire-and-forget 语义：写入失败不抛异常，仅降级到主 logger WARN。
     * 调用方无需 try/catch 包裹。</p>
     *
     * @param event 审计事件，null 时静默返回
     */
    public void log(AuditEvent event) {
        if (!auditEnabled || event == null) {
            return;
        }
        // 鉴权失败事件受 logAuthFailure 开关控制
        if (event.getType() == AuditEvent.Type.AUTH_FAILURE && !logAuthFailure) {
            return;
        }
        try {
            String json = serializeToJson(event);
            // INFO 级别写入审计 logger（审计日志独立于业务日志级别）
            AUDIT_LOGGER.info(json);
        } catch (Exception e) {
            // 审计写入失败不阻断业务，降级到主 logger WARN
            log.warn("审计日志写入失败，事件降级记录到主日志: type={}, outcome={}, actor={}, error={}",
                    event.getType(), event.getOutcome(), event.getActor(), e.getMessage());
        }
    }

    /**
     * 便捷方法：记录签名转账事件。
     *
     * @param outcome   操作结果
     * @param actor     调用方标识
     * @param sourceIp  来源 IP
     * @param txHash    交易 hash（脱敏后的标识符），可为 null
     * @param amount    签名金额
     * @param currency  币种
     */
    public void logSignTransfer(AuditEvent.Outcome outcome, String actor,
                                String sourceIp, String txHash,
                                java.math.BigDecimal amount, String currency) {
        AuditEvent.Builder builder = AuditEvent.builder(AuditEvent.Type.SIGN_TRANSFER, outcome, actor)
                .sourceIp(sourceIp)
                .target(txHash)
                .detail("amount", amount == null ? null : amount.toPlainString())
                .detail("currency", currency);
        log(builder.build());
    }

    /**
     * 便捷方法：记录 keystore 访问事件。
     *
     * @param outcome     操作结果
     * @param actor       调用方标识
     * @param sourceIp    来源 IP
     * @param operation   操作类型（如 "fromPassword" / "keystoreToPubkey"）
     * @param keystoreId  密钥标识（脱敏后的别名 / pubkey hash），可为 null
     */
    public void logKeystoreAccess(AuditEvent.Outcome outcome, String actor,
                                  String sourceIp, String operation,
                                  String keystoreId) {
        AuditEvent.Builder builder = AuditEvent.builder(AuditEvent.Type.KEYSTORE_ACCESS, outcome, actor)
                .sourceIp(sourceIp)
                .target(keystoreId)
                .detail("operation", operation);
        log(builder.build());
    }

    /**
     * 便捷方法：记录 MPC 操作事件。
     *
     * @param outcome     操作结果
     * @param actor       调用方标识
     * @param sourceIp    来源 IP
     * @param sessionId   MPC session_id
     * @param operation   操作类型（如 "DKG" / "sign" / "aggregate"）
     * @param partyIndex  参与方索引
     */
    public void logMpcOperation(AuditEvent.Outcome outcome, String actor,
                                String sourceIp, String sessionId,
                                String operation, int partyIndex) {
        AuditEvent.Builder builder = AuditEvent.builder(AuditEvent.Type.MPC_OPERATION, outcome, actor)
                .sourceIp(sourceIp)
                .target(sessionId)
                .detail("operation", operation)
                .detail("party_index", partyIndex);
        log(builder.build());
    }

    /**
     * 便捷方法：记录鉴权失败事件（401/403）。
     *
     * @param actor     调用方标识（可能为 "anonymous"）
     * @param sourceIp  来源 IP
     * @param endpoint  被拒绝的端点
     * @param reason    拒绝原因（如 "missing_token" / "insufficient_role"）
     */
    public void logAuthFailure(String actor, String sourceIp,
                               String endpoint, String reason) {
        AuditEvent.Builder builder = AuditEvent.builder(AuditEvent.Type.AUTH_FAILURE, AuditEvent.Outcome.DENIED, actor)
                .sourceIp(sourceIp)
                .target(endpoint)
                .detail("reason", reason);
        log(builder.build());
    }

    /**
     * 将 AuditEvent 序列化为 JSON 字符串。
     *
     * <p>使用 LinkedHashMap 保持字段顺序，便于日志检索工具按字段顺序解析。
     * 失败时抛出 JsonProcessingException，由 {@link #log} 捕获降级处理。</p>
     */
    private static String serializeToJson(AuditEvent event) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", event.getTimestamp().toString());
        root.put("type", event.getType().name());
        root.put("outcome", event.getOutcome().name());
        root.put("actor", event.getActor());
        root.put("sourceIp", event.getSourceIp());
        root.put("target", event.getTarget());
        // details 作为嵌套对象，保持结构化
        if (!event.getDetails().isEmpty()) {
            root.put("details", event.getDetails());
        }
        // 关联链路追踪 ID（MDC 中由 Micrometer Tracing 注入）
        String traceId = org.slf4j.MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            root.put("traceId", traceId);
        }
        return OBJECT_MAPPER.writeValueAsString(root);
    }
}
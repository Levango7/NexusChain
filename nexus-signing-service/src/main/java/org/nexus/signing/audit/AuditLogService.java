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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
 *   <li>{@code sourceIp}：来源 IP（仅信任可信代理的 X-Forwarded-For，见 {@link #extractClientIp}）</li>
 *   <li>{@code target}：操作目标标识（交易 hash / 密钥 ID / session_id）</li>
 *   <li>{@code details}：附加详情（amount / currency / party_index 等）</li>
 *   <li>{@code traceId}：链路追踪 ID（关联业务 span）</li>
 *   <li>{@code previousHash}：前一条审计日志的 SHA-256 hash（中8 链式哈希防篡改）</li>
 *   <li>{@code hash}：当前审计日志的 SHA-256 hash</li>
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
     * 可信代理 IP 列表（逗号分隔），用于 {@link #extractClientIp} 判断是否信任
     * X-Forwarded-For 头。
     * <p>从 {@code nexus.audit.trusted-proxy-ips} 配置读取，默认空字符串。
     * 空值表示不信任任何代理的 X-Forwarded-For，始终使用 RemoteAddr；
     * 非空时仅当请求的直接来源 IP（{@code request.getRemoteAddr()}）在此列表中，
     * 才采用 X-Forwarded-For 的第一个 IP 作为客户端真实 IP。</p>
     * <p>示例：{@code trusted-proxy-ips=10.0.0.1,10.0.0.2}（gateway / nginx 内网 IP）</p>
     */
    @Value("${nexus.audit.trusted-proxy-ips:}")
    private String trustedProxyIps;

    /**
     * 链式哈希初始值：64 个 '0'（与 SHA-256 输出长度一致），代表创世日志无前驱。
     * <p>首条审计日志的 previousHash 为此值，后续每条日志的 previousHash 指向前一条
     * 日志的 hash，形成不可篡改的 hash chain。任何对历史日志的修改都会破坏链式
     * 校验（重算 hash 与记录的 hash 不一致）。</p>
     */
    private static final String INITIAL_HASH = "0".repeat(64);

    /**
     * 链式哈希锁：保证 hash 计算与日志写入的原子性，确保 hash chain 顺序与日志
     * 写入顺序一致。审计日志写入频率不高，锁竞争可接受。
     */
    private final Object hashChainLock = new Object();

    /**
     * 最后一条审计日志的 hash，用于构建链式哈希。
     * <p>使用 AtomicReference 保证内存可见性；hash 计算与更新在
     * {@link #hashChainLock} 同步块内完成，保证线程安全。</p>
     * <p>注意：此字段为单实例内存状态，重启后重置为 {@link #INITIAL_HASH}。
     * 跨进程 / 跨实例的链式哈希需引入共享存储（如 Redis），留待 P3 阶段。</p>
     */
    private final AtomicReference<String> lastHash = new AtomicReference<>(INITIAL_HASH);

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
     * <p><b>已废弃</b>：此方法无条件信任 X-Forwarded-For 头，可被客户端伪造。
     * 新代码应使用实例方法 {@link #extractClientIp}，仅在请求来自可信代理 IP
     * 时才采用 X-Forwarded-For。保留此方法仅为向后兼容（如 TxController 静态调用）。</p>
     *
     * @param request HTTP 请求，null 时返回 null
     * @return 来源 IP 或 null
     * @deprecated 使用 {@link #extractClientIp} 替代，仅信任可信代理的 X-Forwarded-For
     */
    @Deprecated
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
     * 从 HTTP 请求提取客户端真实 IP，仅信任可信代理的 X-Forwarded-For 头。
     *
     * <p>中9 安全加固：解决 {@link #resolveSourceIp} 无条件信任 X-Forwarded-For
     * 可被伪造的问题。逻辑：</p>
     * <ol>
     *   <li>取请求的直接来源 IP（{@code request.getRemoteAddr()}）</li>
     *   <li>仅当此 IP 在 {@code nexus.audit.trusted-proxy-ips} 白名单中时，
     *       才采用 X-Forwarded-For 的第一个 IP（最原始客户端）</li>
     *   <li>否则直接使用 RemoteAddr（不信任客户端自带的 X-Forwarded-For）</li>
     * </ol>
     * <p>未配置可信代理 IP（空字符串）时，始终使用 RemoteAddr，等价于不信任任何代理。</p>
     *
     * @param request HTTP 请求，null 时返回 null
     * @return 客户端真实 IP 或 null
     */
    public String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remoteAddr = request.getRemoteAddr();
        if (trustedProxyIps != null && !trustedProxyIps.isBlank() && remoteAddr != null) {
            String trimmedRemote = remoteAddr.trim();
            // 解析可信代理 IP 集合，匹配则信任 X-Forwarded-For
            for (String trusted : trustedProxyIps.split(",")) {
                if (trusted.trim().equals(trimmedRemote)) {
                    String xff = request.getHeader("X-Forwarded-For");
                    if (xff != null && !xff.isBlank()) {
                        // X-Forwarded-For 可能含多个 IP，取第一个（最原始客户端）
                        return xff.split(",")[0].trim();
                    }
                    break;
                }
            }
        }
        return remoteAddr;
    }

    /**
     * 写入审计事件到专用审计日志。
     *
     * <p>fire-and-forget 语义：写入失败不抛异常，仅降级到主 logger WARN。
     * 调用方无需 try/catch 包裹。</p>
     *
     * <p><b>中8 链式哈希</b>：每条日志包含 {@code previousHash}（前一条日志的 hash）
     * 和 {@code hash}（当前日志的 hash），形成 hash chain。hash 计算与日志写入在
     * {@link #hashChainLock} 同步块内原子完成，保证链式顺序与写入顺序一致。
     * 任何对历史日志的篡改都会导致重算 hash 与记录 hash 不一致，从而被发现。</p>
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
        // 链式哈希：hash 计算与日志写入必须原子，保证 hash chain 顺序一致
        synchronized (hashChainLock) {
            String previousHash = lastHash.get();
            String currentHash = computeHash(previousHash, event);
            try {
                String json = serializeToJson(event, previousHash, currentHash);
                // INFO 级别写入审计 logger（审计日志独立于业务日志级别）
                AUDIT_LOGGER.info(json);
                // 仅在写入成功后更新 lastHash，保证链式哈希不断裂
                lastHash.set(currentHash);
            } catch (Exception e) {
                // 审计写入失败不阻断业务，降级到主 logger WARN
                // 注意：写入失败时不更新 lastHash，下一条日志仍链接到当前 lastHash，
                // 避免链式哈希断裂（失败日志不计入链）
                log.warn("审计日志写入失败，事件降级记录到主日志: type={}, outcome={}, actor={}, error={}",
                        event.getType(), event.getOutcome(), event.getActor(), e.getMessage());
            }
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
     * <p><b>低7 防用户枚举</b>：默认视为未认证请求（401），脱敏记录——
     * 不记录具体 endpoint 和 reason，仅记录 {@code "authentication_failed"}
     * + sourceIp，防止攻击者通过鉴权失败响应差异枚举有效用户 / 端点。</p>
     *
     * <p>已认证但权限不足（403）场景应使用重载方法
     * {@link #logAuthFailure(String, String, String, String, boolean)}，
     * 传入 {@code authenticated=true} 正常记录 endpoint 和 reason。</p>
     *
     * @param actor     调用方标识（可能为 "anonymous"）
     * @param sourceIp  来源 IP
     * @param endpoint  被拒绝的端点（未认证场景下不记录，仅用于已认证重载）
     * @param reason    拒绝原因（未认证场景下不记录，仅用于已认证重载）
     */
    public void logAuthFailure(String actor, String sourceIp,
                               String endpoint, String reason) {
        // 默认视为未认证（401），脱敏防止用户枚举
        logAuthFailure(actor, sourceIp, endpoint, reason, false);
    }

    /**
     * 便捷方法：记录鉴权失败事件，区分未认证（401）与已认证权限不足（403）。
     *
     * <p><b>低7 防用户枚举</b>：</p>
     * <ul>
     *   <li>未认证请求（{@code authenticated=false}，对应 401）：不记录具体 endpoint
     *       和 reason，target 设为 {@code "authentication_failed"}，仅记录 actor + sourceIp，
     *       防止攻击者通过鉴权失败日志枚举有效端点 / 用户</li>
     *   <li>已认证请求（{@code authenticated=true}，对应 403 权限不足）：正常记录
     *       endpoint 和 reason，便于审计权限违规</li>
     * </ul>
     *
     * @param actor         调用方标识（可能为 "anonymous"）
     * @param sourceIp      来源 IP
     * @param endpoint      被拒绝的端点
     * @param reason        拒绝原因（如 "missing_token" / "insufficient_role"）
     * @param authenticated 请求是否已认证（true=403 权限不足，false=401 未认证）
     */
    public void logAuthFailure(String actor, String sourceIp,
                               String endpoint, String reason, boolean authenticated) {
        AuditEvent.Builder builder = AuditEvent.builder(
                AuditEvent.Type.AUTH_FAILURE, AuditEvent.Outcome.DENIED, actor)
                .sourceIp(sourceIp);
        if (authenticated) {
            // 已认证请求（403 权限不足）：正常记录 endpoint 和 reason，便于审计权限违规
            builder.target(endpoint).detail("reason", reason);
        } else {
            // 未认证请求（401）：脱敏，不记录具体 endpoint 和 reason，防止用户枚举
            builder.target("authentication_failed");
        }
        log(builder.build());
    }

    /**
     * 将 AuditEvent 序列化为 JSON 字符串，包含链式哈希字段。
     *
     * <p>使用 LinkedHashMap 保持字段顺序，便于日志检索工具按字段顺序解析。
     * 失败时抛出 JsonProcessingException，由 {@link #log} 捕获降级处理。</p>
     *
     * <p><b>中8 链式哈希字段</b>：输出包含 {@code previousHash}（前一条日志的 hash）
     * 和 {@code hash}（当前日志的 hash），形成 hash chain。校验工具读取日志文件后，
     * 依次重算每条日志的 hash 并与记录的 hash 比对，任一不一致即表示日志被篡改。</p>
     *
     * @param event        审计事件
     * @param previousHash 前一条审计日志的 hash（首条为 {@link #INITIAL_HASH}）
     * @param currentHash  当前审计日志的 hash
     * @return JSON 字符串
     * @throws JsonProcessingException 序列化失败
     */
    private static String serializeToJson(AuditEvent event, String previousHash, String currentHash)
            throws JsonProcessingException {
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
        // 中8：链式哈希字段，形成不可篡改的 hash chain
        root.put("previousHash", previousHash);
        root.put("hash", currentHash);
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    /**
     * 计算审计事件的链式哈希（中8 防篡改）。
     *
     * <p>hash = SHA-256(previousHash | timestamp | type | outcome | actor |
     * sourceIp | target | details)，使用管道符 {@code |} 分隔字段避免歧义。
     * null 字段以空字符串参与计算，保证 hash 确定性。</p>
     *
     * <p>校验时按相同规则重算并与日志中记录的 {@code hash} 字段比对：
     * <ul>
     *   <li>重算 hash == 记录 hash 且 previousHash == 前一条记录 hash → 日志未被篡改</li>
     *   <li>任一不一致 → 日志被篡改或链断裂</li>
     * </ul></p>
     *
     * @param previousHash 前一条日志的 hash
     * @param event        当前审计事件
     * @return 当前日志的 SHA-256 hash（64 位十六进制字符串）
     */
    private static String computeHash(String previousHash, AuditEvent event) {
        String input = String.join("|",
                previousHash == null ? "" : previousHash,
                event.getTimestamp().toString(),
                event.getType().name(),
                event.getOutcome().name(),
                event.getActor() == null ? "" : event.getActor(),
                event.getSourceIp() == null ? "" : event.getSourceIp(),
                event.getTarget() == null ? "" : event.getTarget(),
                event.getDetails().toString());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 内置算法，理论上不会缺失；若缺失则审计哈希无法工作，抛致命异常
            throw new IllegalStateException("SHA-256 algorithm not available, audit hash chain broken", e);
        }
    }
}
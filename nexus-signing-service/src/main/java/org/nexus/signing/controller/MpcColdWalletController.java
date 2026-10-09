package org.nexus.signing.controller;

import org.nexus.signing.config.SecurityRoles;
import org.nexus.signing.mpc.ColdWalletMultiSigService;
import org.nexus.signing.mpc.MpcEngineParticipants;
import org.nexus.signing.mpc.MpcKeyGeneration;
import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.DefaultMpcService;
import org.nexus.signing.mpc.MpcWallet;
import org.nexus.signing.mpc.cggmp.CggmpMpcCryptoEngine;
import org.nexus.signing.mpc.persistence.MpcWalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MPC 冷钱包 HTTP 入口层（2026-10-09 新增）。
 *
 * <p><b>为什么需要它</b>：GG20 退役后，CGGMP21 的「钱包密钥生成（DKG 仪式）」与
 * 「冷钱包多签转账编排」两条链在代码上完整、却**没有任何 HTTP 入口**——
 * {@code MpcService.generateKeyShare} 与 {@code ColdWalletMultiSigService} 的
 * init/sign/broadcast 三个方法此前只有测试调用方，生产无法驱动
 * （审计发现：转账→MPC 签名→上链这条业务链"不是没测，而是没接出去"）。
 * 本控制器把这两条链接出来，使业务链可从网关/REST 驱动。</p>
 *
 * <h2>会话约定（与引擎一致，勿改）</h2>
 * <p>钱包的引擎会话 ID = {@link CggmpMpcCryptoEngine#walletSessionId(String)}
 * （{@code cw-} + SHA-256(walletId) 前 16 字节 hex）。**DKG 与每笔签名共用同一
 * 会话 ID**——引擎按此 ID 从磁盘/内存找回份额；用别的 ID 建钱包会让后续签名
 * 报 {@code key_share missing}。</p>
 *
 * <h2>两条阈值口径（勿混）</h2>
 * <ul>
 *   <li><b>加密阈值 t</b>（本控制器的 {@code threshold}，默认 n-1，即 2-of-3）：
 *       引擎侧 t-of-n，决定签名可行性。</li>
 *   <li><b>业务审批法定数</b>（{@code MpcApprovalPolicy} 冷钱包策略）：决定
 *       "这笔转账是否被批准"，两者相互独立、都须通过。</li>
 * </ul>
 *
 * <h2>失败语义（fail-closed）</h2>
 * <ul>
 *   <li>集群不可用（未启用 CGGMP21 / 健康探测失败）→ <b>503</b>：拒绝受理转账，
 *       绝不降级到 skeleton 记账；</li>
 *   <li>未知钱包 / 未达审批法定数 / 地址不在白名单 / 会话状态非法 →
 *       {@code MpcProtocolException} → 409（业务规则拒绝）；</li>
 *   <li>参数缺失或非法 → 400。</li>
 * </ul>
 *
 * <h2>鉴权</h2>
 * <p>钱包初始化（DKG 仪式，动密钥材料）= {@code ROLE_ADMIN}；转账三类操作
 * （init/sign/broadcast）= {@code ROLE_SIGNER}；查询 = {@code ROLE_READ}。
 * 与既有控制器同一套 {@link SecurityRoles} 注解语义。</p>
 */
@RestController
@RequestMapping("/api/v1/mpc")
public class MpcColdWalletController {

    private static final Logger log = LoggerFactory.getLogger(MpcColdWalletController.class);

    /** 业务成功码（与 TxController/NodeController 既有约定一致）。 */
    private static final int BIZ_OK = 2000;
    /** 依赖不可用业务码（503 语义；区别于 5000 业务失败）。 */
    private static final int BIZ_UNAVAILABLE = 5030;

    /** 编排实现（{@code MpcService} 接口只有 3 个遗留 stub；DKG 编排方法在其实现类上）。 */
    private final DefaultMpcService mpcService;
    private final ColdWalletMultiSigService coldWalletService;
    private final MpcWalletRepository walletRepository;
    private final MpcEngineParticipants engineParticipants;
    private final ObjectProvider<CggmpMpcCryptoEngine> cggmpEngineProvider;

    public MpcColdWalletController(DefaultMpcService mpcService,
                                   ColdWalletMultiSigService coldWalletService,
                                   MpcWalletRepository walletRepository,
                                   MpcEngineParticipants engineParticipants,
                                   ObjectProvider<CggmpMpcCryptoEngine> cggmpEngineProvider) {
        this.mpcService = mpcService;
        this.coldWalletService = coldWalletService;
        this.walletRepository = walletRepository;
        this.engineParticipants = engineParticipants;
        this.cggmpEngineProvider = cggmpEngineProvider;
    }

    /**
     * 启动时把持久化钱包回灌进编排服务（{@code ColdWalletMultiSigService} 的钱包
     * 表是内存态；不回灌则重启后所有钱包都报 "unknown MPC wallet"）。
     */
    @PostConstruct
    void rehydrateWallets() {
        try {
            List<MpcWallet> wallets = walletRepository.findAll();
            wallets.forEach(coldWalletService::registerWallet);
            log.info("MPC cold-wallet controller ready: rehydrated {} wallet(s) from repository",
                    wallets.size());
        } catch (RuntimeException e) {
            // 回灌失败不阻断启动（后续 init 转账时会按需再注册），但必须显式告警
            log.warn("MPC cold-wallet wallet rehydration failed (will lazily register on demand): {}",
                    e.getMessage());
        }
    }

    // =========================================================================
    // 钱包初始化（DKG 仪式：keygen → aux → assemble，份额驻留引擎）
    // =========================================================================

    /** 钱包初始化请求体。{@code threshold} 缺省 = n-1（3 引擎 → 2-of-3）。 */
    public record InitWalletRequest(String walletId, String label, Integer threshold) {
    }

    /**
     * 初始化 MPC 冷钱包：对引擎集群跑一次 CGGMP21 DKG 仪式并登记钱包。
     *
     * <p>幂等：钱包已存在时直接返回既有记录（不重跑仪式——引擎侧对同会话的
     * 重入会从磁盘恢复既有份额并幂等返回，重跑无收益）。</p>
     *
     * <p>fail-closed：引擎集群不可用（未启用 CGGMP21 / 健康探测失败）→ 409
     * （{@code ILLEGAL_STATE}）拒绝建钱包——没有真实引擎就产不出份额，
     * 静默占坑比拒绝更糟。</p>
     */
    @PreAuthorize("hasRole('" + SecurityRoles.ADMIN + "')")
    @PostMapping("/wallets")
    public Map<String, Object> initWallet(@RequestBody InitWalletRequest request) {
        if (request == null || request.walletId() == null || request.walletId().isBlank()) {
            throw new IllegalArgumentException("walletId is required");
        }
        String walletId = request.walletId().trim();

        MpcWallet existing = walletRepository.findById(walletId).orElse(null);
        if (existing != null) {
            coldWalletService.registerWallet(existing);
            log.info("MPC wallet init idempotent hit: walletId={}, publicKey={}...",
                    walletId, abbreviate(existing.getPublicKey()));
            return ok(walletData(existing, null, true));
        }

        List<MpcParticipant> participants = engineParticipants.participants();
        int n = participants.size();
        int t = request.threshold() != null ? request.threshold() : Math.max(1, n - 1);
        if (t < 1 || t > n) {
            throw new IllegalArgumentException(
                    "threshold must be in [1, " + n + "] (engine count), got " + t);
        }

        // 集群不可用 → 拒绝建钱包（与转账受理同款 fail-closed：没有真实引擎就建不出份额，
        // 静默失败或占用钱包 ID 都更糟；幂等分支不受影响——已建钱包无需集群也能查询/登记）
        if (!isClusterAvailable()) {
            throw new org.nexus.signing.mpc.MpcProtocolException(
                    org.nexus.signing.mpc.MpcProtocolException.Reason.ILLEGAL_STATE,
                    "MPC engine cluster unavailable — cannot run wallet DKG ceremony "
                            + "(fail-closed; check mpc.engine.cggmp-enabled and engine health)");
        }

        // 引擎会话 ID：钱包维度稳定值（DKG 与签名共用；见类注释"会话约定"）
        String engineSessionId = CggmpMpcCryptoEngine.walletSessionId(walletId);

        log.info("MPC wallet DKG start: walletId={}, engineSession={}, t={}, n={}",
                walletId, engineSessionId, t, n);
        MpcKeyGeneration.DkgResult result = mpcService.generateKeyShare(
                engineSessionId,
                t,
                n,
                // partyIndex 对 CGGMP21 不参与路由（驱动方持有全部参与方）；沿用 0
                0,
                participants.get(0).getParticipantId(),
                "secp256k1",
                participants);

        MpcWallet wallet = new MpcWallet();
        wallet.setWalletId(walletId);
        wallet.setLabel(request.label());
        wallet.setParticipants(participants.stream()
                .map(MpcParticipant::getParticipantId)
                .collect(Collectors.toList()));
        wallet.setThreshold(t);
        wallet.setPublicKey(result.getJointPublicKeyHex());
        wallet.setCreatedAt(LocalDateTime.now());
        walletRepository.save(wallet);
        coldWalletService.registerWallet(wallet);

        log.info("MPC wallet DKG done: walletId={}, aggPk={}..., localShares={} "
                        + "(CGGMP21 份额驻留引擎进程)",
                walletId, abbreviate(wallet.getPublicKey()), result.getShares().size());

        Map<String, Object> data = walletData(wallet, engineSessionId, false);
        data.put("localShares", result.getShares().size());
        return ok(data);
    }

    /** 查询钱包（只读）。 */
    @PreAuthorize("hasRole('" + SecurityRoles.READ + "')")
    @GetMapping("/wallets/{walletId}")
    public ResponseEntity<Map<String, Object>> getWallet(@PathVariable String walletId) {
        return walletRepository.findById(walletId)
                .map(w -> ResponseEntity.ok(ok(walletData(w, null, false))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(envelope(
                        BIZ_OK, "unknown wallet: " + walletId, "")));
    }

    // =========================================================================
    // 冷钱包多签转账（init → sign → broadcast）
    // =========================================================================

    /** 转账初始化请求体。 */
    public record InitTransferRequest(String walletId,
                                      String fromAddress,
                                      String toAddress,
                                      BigDecimal amount,
                                      String asset,
                                      String requestId) {
    }

    /**
     * 受理一笔冷钱包多签转账（不签名，仅登记会话并做审批/白名单校验）。
     *
     * <p>集群不可用 → 503（fail-closed）；钱包未知 / 审批未达法定数 / 地址不在
     * 白名单 → 409（{@code MpcProtocolException}）。</p>
     */
    @PreAuthorize("hasRole('" + SecurityRoles.SIGNER + "')")
    @PostMapping("/cold-wallet/transfers")
    public ResponseEntity<Map<String, Object>> initTransfer(@RequestBody InitTransferRequest request) {
        requireTransferRequest(request);

        MpcWallet wallet = walletRepository.findById(request.walletId().trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown wallet: " + request.walletId()
                                + " (initialize it first via POST /api/v1/mpc/wallets)"));
        coldWalletService.registerWallet(wallet); // 懒回灌（多副本/重启后）

        if (!isClusterAvailable()) {
            log.warn("MPC cold-wallet transfer rejected: engine cluster unavailable "
                    + "(cggmp-enabled={}, walletId={})", isCggmpEnabled(), request.walletId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(envelope(
                    BIZ_UNAVAILABLE,
                    "MPC engine cluster unavailable — refusing to accept transfer "
                            + "(fail-closed; check mpc.engine.cggmp-enabled and engine health)",
                    ""));
        }

        List<MpcParticipant> online = engineParticipants.participants().stream()
                .map(p -> p.withOnline(true))
                .collect(Collectors.toList());

        String sessionId = coldWalletService.initMultiSigTransfer(
                wallet.getWalletId(),
                request.fromAddress().trim(),
                request.toAddress().trim(),
                request.amount(),
                request.asset(),
                request.requestId().trim(),
                online);

        log.info("MPC cold-wallet transfer accepted: session={}, wallet={}, amount={} {}, requestId={}",
                sessionId, wallet.getWalletId(), request.amount(), request.asset(), request.requestId());
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("walletId", wallet.getWalletId());
        data.put("status", String.valueOf(coldWalletService.getSessionStatus(sessionId)));
        return ResponseEntity.ok(ok(data));
    }

    /** 执行 MPC 签名（引擎侧产出 r||s 并做内部验签）。 */
    @PreAuthorize("hasRole('" + SecurityRoles.SIGNER + "')")
    @PostMapping("/cold-wallet/transfers/{sessionId}/sign")
    public Map<String, Object> signTransfer(@PathVariable String sessionId) {
        coldWalletService.participantSign(sessionId);
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("status", String.valueOf(coldWalletService.getSessionStatus(sessionId)));
        return ok(data);
    }

    /** 聚合签名并广播上链（经 node RPC），返回链上交易哈希。 */
    @PreAuthorize("hasRole('" + SecurityRoles.SIGNER + "')")
    @PostMapping("/cold-wallet/transfers/{sessionId}/broadcast")
    public Map<String, Object> broadcastTransfer(@PathVariable String sessionId) {
        String txHash = coldWalletService.aggregateAndBroadcast(sessionId);
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("txHash", txHash);
        data.put("status", String.valueOf(coldWalletService.getSessionStatus(sessionId)));
        return ok(data);
    }

    /** 查询转账状态（含失败原因与链上哈希）。 */
    @PreAuthorize("hasRole('" + SecurityRoles.READ + "')")
    @GetMapping("/cold-wallet/transfers/{sessionId}")
    public ResponseEntity<Map<String, Object>> getTransfer(@PathVariable String sessionId) {
        ColdWalletMultiSigService.TransferStatus status =
                coldWalletService.getSessionStatus(sessionId);
        if (status == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(envelope(BIZ_OK, "unknown transfer session: " + sessionId, ""));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("status", status.name());
        data.put("txHash", coldWalletService.getChainTxHash(sessionId));
        data.put("failureReason", coldWalletService.getFailureReason(sessionId));
        return ResponseEntity.ok(ok(data));
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private void requireTransferRequest(InitTransferRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        requireNonBlank(request.walletId(), "walletId");
        requireNonBlank(request.fromAddress(), "fromAddress");
        requireNonBlank(request.toAddress(), "toAddress");
        requireNonBlank(request.asset(), "asset");
        requireNonBlank(request.requestId(), "requestId");
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    /** 集群可用性：启用 CGGMP21 且健康探测通过（否则一律拒绝受理，fail-closed）。 */
    private boolean isClusterAvailable() {
        CggmpMpcCryptoEngine engine = cggmpEngineProvider.getIfAvailable();
        return engine != null && engine.isCggmpEnabled() && engine.healthCheck();
    }

    private boolean isCggmpEnabled() {
        CggmpMpcCryptoEngine engine = cggmpEngineProvider.getIfAvailable();
        return engine != null && engine.isCggmpEnabled();
    }

    private Map<String, Object> walletData(MpcWallet wallet, String engineSessionId,
                                           boolean alreadyInitialized) {
        Map<String, Object> data = new HashMap<>();
        data.put("walletId", wallet.getWalletId());
        data.put("publicKey", wallet.getPublicKey());
        data.put("threshold", wallet.getThreshold());
        data.put("participants", wallet.getParticipants());
        data.put("status", String.valueOf(wallet.getStatus()));
        data.put("alreadyInitialized", alreadyInitialized);
        data.put("engineSessionId",
                engineSessionId != null ? engineSessionId
                        : CggmpMpcCryptoEngine.walletSessionId(wallet.getWalletId()));
        return data;
    }

    private static Map<String, Object> ok(Object data) {
        return envelope(BIZ_OK, "ok", data);
    }

    private static Map<String, Object> envelope(int statusCode, String message, Object data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("statusCode", statusCode);
        payload.put("message", message);
        payload.put("data", data);
        return payload;
    }

    private static String abbreviate(String hex) {
        if (hex == null) {
            return "null";
        }
        return hex.substring(0, Math.min(16, hex.length()));
    }
}

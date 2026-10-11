package org.nexus.walletsvc.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 钱包侧审计服务（P0 补齐 2026-10-11）。
 *
 * <p><b>旁路铁律</b>：审计写失败（DB 抖动、表未建等）**绝不阻断资金流程**——
 * 提现请求/审批/执行不能因为审计故障而卡死；失败只记 WARN 留痕。</p>
 *
 * <p>四个关键节点（与网关侧审计同一套命名风格）：
 * {@code WITHDRAWAL_REQUESTED} / {@code WITHDRAWAL_APPROVED} /
 * {@code WITHDRAWAL_EXECUTED}（带链上哈希）/ {@code WITHDRAWAL_FAILED}（带失败原因）。</p>
 */
@Service
public class WalletAuditService {

    private static final Logger log = LoggerFactory.getLogger(WalletAuditService.class);

    private final WalletAuditEventRepository repository;

    public WalletAuditService(WalletAuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一条提现审计（旁路，失败不抛）。
     *
     * @param action     动作（见类注释四个节点）
     * @param requestId  提现请求 ID
     * @param toAddress  收款地址（资金去向）
     * @param amount     金额
     * @param currency   币种
     * @param detail     附加信息（审批人 / txHash / 失败原因）
     */
    public void record(String action, String requestId, String toAddress,
                       Object amount, String currency, String detail) {
        try {
            WalletAuditEvent e = new WalletAuditEvent();
            e.setEventTime(LocalDateTime.now());
            e.setAction(action);
            e.setRequestId(requestId);
            e.setToAddress(toAddress);
            e.setAmount(amount == null ? null : String.valueOf(amount));
            e.setCurrency(currency);
            e.setDetail(detail);
            repository.save(e);
        } catch (RuntimeException e) {
            // 旁路：绝不向上抛（否则提现流程会被审计故障卡死）
            log.warn("[WalletAudit] 审计写入失败（已忽略，不影响资金流程）: action={}, requestId={}, error={}",
                    action, requestId, e.getMessage());
        }
    }
}

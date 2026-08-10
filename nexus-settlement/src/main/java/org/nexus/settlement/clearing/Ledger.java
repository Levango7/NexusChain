package org.nexus.settlement.clearing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存账本组件。
 * <p>
 * 提供复式记账能力：每笔结算落账产生两条分录——
 * 借：待结算负债（SETTLEMENT_PAYABLE），贷：商户可用余额（MERCHANT:{merchantId}）。
 * 账户净额 = 贷方合计 - 借方合计。
 * </p>
 * <p>
 * 当前为内存实现，重启后丢失；生产接入持久化存储后替换。
 * </p>
 */
@Component
public class Ledger {

    /** 待结算负债账户名 */
    public static final String SETTLEMENT_PAYABLE = "SETTLEMENT_PAYABLE";

    /** 账户 → 分录列表 */
    private final Map<String, List<LedgerEntry>> entriesByAccount = new ConcurrentHashMap<>();

    /** 账户 → 余额（贷方为正，借方为负） */
    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();

    /**
     * 记录一笔结算落账（复式分录）。
     * <p>
     * 借：待结算负债减少；贷：商户可用余额增加。
     * </p>
     *
     * @param merchantId 商户 ID
     * @param amount     结算金额（正数）
     * @param reference  关联业务凭证（如清算订单 ID）
     */
    public void bookSettlement(String merchantId, BigDecimal amount, String reference) {
        if (merchantId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Instant now = Instant.now();
        // 借：待结算负债（减少负债）
        post(SETTLEMENT_PAYABLE, LedgerEntry.Direction.DEBIT, amount, reference, now);
        // 贷：商户可用余额（增加资产）
        post("MERCHANT:" + merchantId, LedgerEntry.Direction.CREDIT, amount, reference, now);
    }

    /**
     * 记录一笔归集落账：借：归集目标账户（增加资产），贷：源账户（减少资产）。
     *
     * @param sourceAccount 源账户名
     * @param targetAccount 目标账户名
     * @param amount        金额（正数）
     * @param reference     关联凭证
     */
    public void bookTransfer(String sourceAccount, String targetAccount,
                             BigDecimal amount, String reference) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Instant now = Instant.now();
        post(targetAccount, LedgerEntry.Direction.CREDIT, amount, reference, now);
        post(sourceAccount, LedgerEntry.Direction.DEBIT, amount, reference, now);
    }

    /**
     * 查询账户余额（贷方为正，借方为负）。
     *
     * @param account 账户名
     * @return 余额，无记录返回 ZERO
     */
    public BigDecimal balanceOf(String account) {
        return balances.getOrDefault(account, BigDecimal.ZERO);
    }

    /**
     * 查询账户全部历史分录。
     *
     * @param account 账户名
     * @return 分录列表（追加顺序）
     */
    public List<LedgerEntry> entriesOf(String account) {
        return List.copyOf(entriesByAccount.getOrDefault(account, List.of()));
    }

    private void post(String account, LedgerEntry.Direction direction,
                      BigDecimal amount, String reference, Instant bookedAt) {
        LedgerEntry entry = new LedgerEntry(
                UUID.randomUUID().toString(), account, direction, amount, reference, bookedAt);
        entriesByAccount.computeIfAbsent(account, k -> new CopyOnWriteArrayList<>()).add(entry);
        balances.merge(account,
                direction == LedgerEntry.Direction.CREDIT ? amount : amount.negate(),
                BigDecimal::add);
    }
}

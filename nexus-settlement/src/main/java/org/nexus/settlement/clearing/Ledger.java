package org.nexus.settlement.clearing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 账本组件（清结算账务核心持久化）。
 * <p>
 * 提供复式记账能力：每笔结算落账产生两条分录——
 * 借：待结算负债（SETTLEMENT_PAYABLE），贷：商户可用余额（MERCHANT:{merchantId}）。
 * 账户净额 = 贷方合计 - 借方合计。
 * </p>
 *
 * <p>持久化设计（持久化落地）：双模式
 * <ul>
 *   <li><b>DB 模式</b>：通过 {@code @Autowired(required=false)} 注入 {@link JdbcTemplate} 与
 *       {@link PlatformTransactionManager}，分录落库到 {@code ledger_entry} 表，
 *       借/贷两条写入包裹在同一 {@link TransactionTemplate} 事务中原子提交/回滚；
 *       撞 {@code (reference, account)} 唯一键即整体回滚，防重复记账。</li>
 *   <li><b>内存模式</b>：保持原内存语义（无 jdbcTemplate 时），供纯单元测试
 *       {@code new Ledger()} 使用，零破坏。</li>
 * </ul>
 * </p>
 */
@Component
public class Ledger {

    /** 待结算负债账户名 */
    public static final String SETTLEMENT_PAYABLE = "SETTLEMENT_PAYABLE";

    /** 账户 → 分录列表（内存模式使用） */
    private final Map<String, List<LedgerEntry>> entriesByAccount = new ConcurrentHashMap<>();

    /** 账户 → 余额（内存模式使用） */
    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();

    /** 持久化 JdbcTemplate（null 则走内存模式） */
    private final JdbcTemplate jdbcTemplate;

    /** 事务管理器（DB 模式用于编程式事务） */
    private final PlatformTransactionManager transactionManager;

    /** 懒构建的编程式事务模板 */
    private volatile TransactionTemplate transactionTemplate;

    /** 纯内存构造器（既有测试 new Ledger() 走此路径） */
    public Ledger() {
        this(null, null);
    }

    /**
     * 持久化构造器。两个依赖均由 Spring 提供；
     * {@code required=false} 保证 settlement 库模块单独装配/单元测试时不强行要求 DataSource。
     *
     * @param jdbcTemplate       数据访问（null 时回退内存模式）
     * @param transactionManager 事务管理器（null 时回退内存模式）
     */
    @Autowired
    public Ledger(@Autowired(required = false) JdbcTemplate jdbcTemplate,
                  @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
    }

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
        if (dbMode()) {
            transactionTemplate().executeWithoutResult(s -> {
                persist(SETTLEMENT_PAYABLE, LedgerEntry.Direction.DEBIT, amount, reference, now);
                persist("MERCHANT:" + merchantId, LedgerEntry.Direction.CREDIT, amount, reference, now);
            });
            return;
        }
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
        if (dbMode()) {
            transactionTemplate().executeWithoutResult(s -> {
                persist(targetAccount, LedgerEntry.Direction.CREDIT, amount, reference, now);
                persist(sourceAccount, LedgerEntry.Direction.DEBIT, amount, reference, now);
            });
            return;
        }
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
        if (!dbMode()) {
            return balances.getOrDefault(account, BigDecimal.ZERO);
        }
        BigDecimal b = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0) "
                        + "FROM ledger_entry WHERE account = ?",
                BigDecimal.class, account);
        return b == null ? BigDecimal.ZERO : b;
    }

    /**
     * 查询账户全部历史分录。
     *
     * @param account 账户名
     * @return 分录列表（按入账时间、ID 追加顺序）
     */
    public List<LedgerEntry> entriesOf(String account) {
        if (!dbMode()) {
            return List.copyOf(entriesByAccount.getOrDefault(account, List.of()));
        }
        return jdbcTemplate.query(
                "SELECT entry_id, account, direction, amount, reference, booked_at "
                        + "FROM ledger_entry WHERE account = ? ORDER BY booked_at, id",
                (rs, rowNum) -> new LedgerEntry(
                        rs.getString("entry_id"),
                        rs.getString("account"),
                        LedgerEntry.Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("amount"),
                        rs.getString("reference"),
                        rs.getTimestamp("booked_at").toInstant()),
                account);
    }

    // ============ 内部 ============

    private boolean dbMode() {
        return jdbcTemplate != null;
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate tt = transactionTemplate;
        if (tt == null) {
            synchronized (this) {
                tt = transactionTemplate;
                if (tt == null) {
                    tt = new TransactionTemplate(transactionManager);
                    transactionTemplate = tt;
                }
            }
        }
        return tt;
    }

    /** DB 模式：单行分录落库 */
    private void persist(String account, LedgerEntry.Direction direction,
                         BigDecimal amount, String reference, Instant bookedAt) {
        jdbcTemplate.update(
                "INSERT INTO ledger_entry(entry_id, account, direction, amount, reference, booked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), account, direction.name(), amount, reference, bookedAt);
    }

    /** 内存模式：原语义分录入账 */
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
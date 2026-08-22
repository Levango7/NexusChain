package org.nexus.consensus.finality.persistence;

import org.nexus.consensus.pos.ValidatorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;

/**
 * 验证人集合跨节点持久化（PLAN-001 步骤 5：落库重放）。
 *
 * <p>多节点共享同一 Postgres → 验证人集合写入共享表 {@code validators_synced}，
 * 实现：</p>
 * <ul>
 *   <li><b>写</b>：本节点自举注册 / 收到对端 validator-set 广播时写库（幂等 MERGE）</li>
 *   <li><b>读（重放）</b>：节点启动时从库加载全部验证人 → 注册到 {@link ValidatorRegistry}，
 *       重启后即使广播时序错失也能恢复全网验证人集合</li>
 * </ul>
 */
@Component
public class ValidatorSetPersistence {

    private static final Logger log = LoggerFactory.getLogger(ValidatorSetPersistence.class);

    private final JdbcTemplate jdbc;
    private final ValidatorRegistry validatorRegistry;

    public ValidatorSetPersistence(@Autowired(required = false) JdbcTemplate jdbc,
                                   @Autowired(required = false) ValidatorRegistry validatorRegistry) {
        this.jdbc = jdbc;
        this.validatorRegistry = validatorRegistry;
    }

    @PostConstruct
    public void init() {
        if (jdbc == null || validatorRegistry == null) {
            log.info("ValidatorSetPersistence disabled (no jdbc/registry)");
            return;
        }
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS validators_synced ("
                + "address VARCHAR(128) PRIMARY KEY, "
                + "public_key VARCHAR(256) NOT NULL, "
                + "stake_amount VARCHAR(64) NOT NULL, "
                + "updated_at BIGINT NOT NULL)");
            log.info("ValidatorSetPersistence: schema initialized");
            replay();
        } catch (RuntimeException e) {
            log.error("ValidatorSetPersistence init failed: {}", e.getMessage());
        }
    }

    /** 启动重放：从共享表加载全部验证人注册到本地 Registry（幂等）。 */
    public void replay() {
        try {
            jdbc.query("SELECT address, public_key, stake_amount FROM validators_synced", rs -> {
                while (rs.next()) {
                    String addr = rs.getString("address");
                    if (validatorRegistry.getValidator(addr) == null) {
                        boolean ok = validatorRegistry.register(
                                addr, rs.getString("public_key"),
                                new BigDecimal(rs.getString("stake_amount")), 0.1);
                        log.info("Validator replay: address={} registered={}", addr, ok);
                    }
                }
            });
        } catch (RuntimeException e) {
            log.error("ValidatorSetPersistence replay failed: {}", e.getMessage());
        }
    }

    /** 写库（本节点自举注册 / 收到广播时调用；幂等 upsert）。 */
    public void upsert(String address, String publicKey, BigDecimal stakeAmount) {
        if (jdbc == null || address == null || publicKey == null || stakeAmount == null) {
            return;
        }
        try {
            // Postgres 幂等 upsert（MERGE 是 H2 语法，PG 报 bad SQL grammar——真机实证）
            jdbc.update(
                    "INSERT INTO validators_synced(address, public_key, stake_amount, updated_at) "
                    + "VALUES(?,?,?,?) "
                    + "ON CONFLICT(address) DO UPDATE SET "
                    + "public_key=EXCLUDED.public_key, stake_amount=EXCLUDED.stake_amount, "
                    + "updated_at=EXCLUDED.updated_at",
                    address, publicKey, stakeAmount.toPlainString(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("Validator upsert failed: address={}, error={}", address, e.getMessage());
        }
    }

    /** 移除（验证人退出时）。 */
    public void remove(String address) {
        if (jdbc == null || address == null) return;
        try {
            jdbc.update("DELETE FROM validators_synced WHERE address=?", address);
        } catch (RuntimeException e) {
            log.warn("Validator remove failed: address={}, error={}", address, e.getMessage());
        }
    }
}

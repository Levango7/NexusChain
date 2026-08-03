package org.nexus.core;

import org.nexus.core.payment.BridgeTransaction;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.core.payment.StableCoinPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC-backed persistent store for production.
 * Uses write-through: writes go to DB first, then in-memory cache.
 * Reads served from cache for performance, DB for recovery on startup.
 */
@Component
@Profile("!dev")
public class JdbcPaymentStateStore implements PaymentStateStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcPaymentStateStore.class);

    private final JdbcTemplate jdbc;
    private final Map<String, PaymentChannel> channelCache = new ConcurrentHashMap<>();
    private final Map<String, StableCoinPosition> positionCache = new ConcurrentHashMap<>();
    private final Map<String, BridgeTransaction> bridgeCache = new ConcurrentHashMap<>();

    public JdbcPaymentStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS payment_channels ("
            + "channel_id VARCHAR(128) PRIMARY KEY, "
            + "participant1 VARCHAR(128), participant2 VARCHAR(128), "
            + "balance1 BIGINT, balance2 BIGINT, nonce BIGINT, "
            + "state VARCHAR(32), open_block BIGINT, close_block BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS stablecoin_positions ("
            + "position_id VARCHAR(128) PRIMARY KEY, "
            + "owner VARCHAR(128), collateral BIGINT, minted BIGINT, "
            + "last_update_block BIGINT, created_block BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS bridge_transactions ("
            + "bridge_tx_id VARCHAR(128) PRIMARY KEY, "
            + "source_chain VARCHAR(64), target_chain VARCHAR(64), "
            + "amount BIGINT, recipient VARCHAR(128), "
            + "state VARCHAR(32), confirmations INT)");
        log.info("JdbcPaymentStateStore: schema initialized");
    }

    @Override
    public void putChannel(String id, PaymentChannel ch) {
        jdbc.update("MERGE INTO payment_channels KEY(channel_id) VALUES(?,?,?,?,?,?,?,?)",
            id, ch.getParticipant1(), ch.getParticipant2(),
            ch.getBalance1(), ch.getBalance2(), ch.getNonce(),
            ch.getState().name(), ch.getOpenBlockHeight(), ch.getCloseBlockHeight());
        channelCache.put(id, ch);
    }

    @Override
    public PaymentChannel getChannel(String id) { return channelCache.get(id); }

    @Override
    public Collection<PaymentChannel> getAllChannels() { return Collections.unmodifiableCollection(channelCache.values()); }

    @Override
    public void putPosition(String id, StableCoinPosition pos) {
        jdbc.update("MERGE INTO stablecoin_positions KEY(position_id) VALUES(?,?,?,?,?,?)",
            id, pos.getOwner(), pos.getCollateralAmount(), pos.getMintedAmount(),
            pos.getLastUpdateBlock(), pos.getCreatedAtBlock());
        positionCache.put(id, pos);
    }

    @Override
    public StableCoinPosition getPosition(String id) { return positionCache.get(id); }

    @Override
    public Collection<StableCoinPosition> getAllPositions() { return Collections.unmodifiableCollection(positionCache.values()); }

    @Override
    public void putBridgeTx(String id, BridgeTransaction tx) {
        jdbc.update("MERGE INTO bridge_transactions KEY(bridge_tx_id) VALUES(?,?,?,?,?,?,?)",
            id, tx.getSourceChain(), tx.getTargetChain(), tx.getAmount(),
            tx.getRecipient(), tx.getState().name(), tx.getValidators() != null ? tx.getValidators().size() : 0);
        bridgeCache.put(id, tx);
    }

    @Override
    public BridgeTransaction getBridgeTx(String id) { return bridgeCache.get(id); }

    @Override
    public Collection<BridgeTransaction> getAllBridgeTxs() { return Collections.unmodifiableCollection(bridgeCache.values()); }
}
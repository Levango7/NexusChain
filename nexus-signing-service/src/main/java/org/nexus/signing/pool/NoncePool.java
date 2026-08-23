package org.nexus.signing.pool;

import org.nexus.sdk.util.JsonUtil;
import org.nexus.signing.storage.Leveldb;
import org.nexus.signing.util.BeanToMapUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nonce 池（LevelDB 持久化）。
 *
 * <p>从 {@code org.nexus.wallet.wallet.pool.NoncePool}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.pool}。</p>
 *
 * <p>签名服务管理 Nonce 池作为签名前置状态，紧耦合签名流程。
 * LevelDB 持久化保证服务重启后 Nonce 不丢失。</p>
 *
 * <p>Phase 3 任务 #62：新增 TCC 预锁定能力（{@link #lockNonce} /
 * {@link #confirmNonce} / {@link #cancelNonce}），用于 Seata TCC 分布式事务。
 * 非 TCC 路径走原逻辑（{@code getMaxNonce} 直接获取 + {@code add} 使用，无预锁定），
 * 保持向后兼容（设计文档 §4.2.4 / 风险 R2）。</p>
 */
@Component
public class NoncePool {

    private static final Logger log = LoggerFactory.getLogger(NoncePool.class);

    private ConcurrentHashMap<String, TreeMap<Long, NonceState>> noncepool;
    private Leveldb leveldb;

    /**
     * TCC 预锁定表：address → 当前锁定的 nonce。
     *
     * <p>Try 阶段写入，Confirm/Cancel 阶段移除。同一 address 同时只能有一个锁定 nonce，
     * 避免并发 TCC 事务争用同一 nonce（设计文档 §3.1.2 决策理由 2）。</p>
     *
     * <p>注：锁定表为内存态（不持久化），服务重启后锁定丢失——可接受，因为 Seata TM
     * 会重试 Confirm/Cancel，重试时锁定已不在，按"幂等释放"处理。</p>
     */
    private final ConcurrentHashMap<String, Long> lockedNonces = new ConcurrentHashMap<>();

    @Autowired
    public NoncePool(Leveldb leveldb) {
        this.leveldb=leveldb;
        try{
            this.noncepool = new ConcurrentHashMap<>();
            //序列化获取
            String dbdata=leveldb.readFromSnapshot();
            if(dbdata!=null && !dbdata.equals("")){
                Map<String, Map<Integer, NonceState>> maps= JsonUtil.GSON.fromJson(dbdata,HashMap.class);
                for(Map.Entry<String, Map<Integer, NonceState>> entry:maps.entrySet()){
                    String key=entry.getKey();
                    Map<Integer, NonceState> map=entry.getValue();
                    TreeMap<Long, NonceState> treemap=new TreeMap<>();
                    for(Map.Entry<Integer, NonceState> entry1:map.entrySet()){
                        int treekey=entry1.getKey();
                        long key2=treekey;
                        NonceState nonceState= (NonceState) BeanToMapUtil.convertMap(NonceState.class, (Map) entry1.getValue());
                        treemap.put(key2,nonceState);
                    }
                    noncepool.put(key,treemap);
                }
            }
        }catch (Exception e){
            log.warn("Failed to load nonce pool from LevelDB, falling back to empty pool", e);
            this.noncepool = new ConcurrentHashMap<>();
        }
    }

    public ConcurrentHashMap<String, TreeMap<Long, NonceState>> getNoncepool() {
        return noncepool;
    }

    public void add(String address,NonceState nonceState) throws IOException {
        if(noncepool.containsKey(address)){
            TreeMap<Long, NonceState> tmaps=noncepool.get(address);
            long nownonce=nonceState.getNonce();
            if(!tmaps.containsKey(nownonce)){
                tmaps.put(nonceState.getNonce(),nonceState);
            }
        }else{
            TreeMap<Long, NonceState> treemap=new TreeMap<>();
            treemap.put(nonceState.getNonce(),nonceState);
            noncepool.put(address,treemap);
        }
        String json = JsonUtil.GSON_PRETTY.toJson(noncepool);
        leveldb.addPoolDb(json);
    }

    public void remove(String address,long nonce) throws IOException {
        if(noncepool.containsKey(address)){
            TreeMap<Long, NonceState> tmaps=noncepool.get(address);
            if(tmaps.containsKey(nonce)){
                tmaps.remove(nonce);
            }
            if(tmaps.size()==0){
                noncepool.remove(address);
            }
        }
        String json = JsonUtil.GSON_PRETTY.toJson(noncepool);
        leveldb.addPoolDb(json);
    }

    public long getMinNonce(String address){
        if(noncepool.containsKey(address)){
            TreeMap<Long, NonceState> tmaps=noncepool.get(address);
            return tmaps.firstKey();
        }
        return 0;
    }

    public long getMaxNonce(String address){
        if(noncepool.containsKey(address)){
            TreeMap<Long, NonceState> tmaps=noncepool.get(address);
            return tmaps.lastKey();
        }
        return 0;
    }

    public TreeMap<Long, NonceState> getTreemap(String address) {
        if (noncepool.containsKey(address)) {
            return noncepool.get(address);
        }
        return new TreeMap<>();
    }

    // ==================== Phase 3 任务 #62：TCC 预锁定 API ====================

    /**
     * 预锁定 nonce（TCC Try 阶段调用）——获取当前 maxNonce 并标记为"已锁定"。
     *
     * <p>若该 address 已有锁定 nonce，返回 -1 表示锁定冲突（调用方应抛异常触发全局回滚）。
     * 若 maxNonce==0（池为空，首次使用该 address），返回 0——调用方需通过 RPC
     * {@code NodeController.getNonce} 获取链上 nonce 后再调 {@link #lockNonce(String, long)}
     * 锁定具体值。</p>
     *
     * @param address 钱包地址（由 fromPubkey → pubkeyHash → address 推导）
     * @return 锁定的 nonce；0 表示池为空需 RPC 获取；-1 表示锁定冲突
     */
    public long lockNonce(String address) {
        long maxNonce = getMaxNonce(address);
        if (maxNonce == 0) {
            // 池为空，调用方需 RPC 获取后调 lockNonce(address, rpcNonce)
            return 0;
        }
        return lockNonce(address, maxNonce);
    }

    /**
     * 预锁定指定 nonce（TCC Try 阶段 RPC 获取后调用）——把指定 nonce 标记为"已锁定"。
     *
     * @param address 钱包地址
     * @param nonce   待锁定的 nonce（通常来自 RPC getNonce 或 maxNonce）
     * @return 锁定成功返回 nonce；-1 表示该 address 已有锁定（冲突）
     */
    public long lockNonce(String address, long nonce) {
        if (nonce <= 0) {
            return -1;
        }
        // putIfAbsent 保证原子性：若已存在锁定返回既有值（≠nonce 即冲突）
        Long existing = lockedNonces.putIfAbsent(address, nonce);
        if (existing != null && existing != nonce) {
            log.warn("lockNonce conflict: address={} already locked at nonce={}, requested={}",
                    address, existing, nonce);
            return -1;
        }
        log.debug("lockNonce success: address={} nonce={}", address, nonce);
        return nonce;
    }

    /**
     * 确认 nonce 已使用（TCC Confirm 阶段调用）——释放锁定并标记为"已使用"。
     *
     * <p>从预锁定表移除，并把 {@code nonce+1} 作为下一个可用 nonce 写入 NoncePool
     * （与 {@code TxController.signAndBroadcast} 现有逻辑一致：nownonce++ 后 add）。
     * 调用方（SigningTccAction.Confirm）应在签名广播成功后调用本方法。</p>
     *
     * @param address 钱包地址
     * @param nonce   Try 阶段锁定的 nonce（已用于签名广播）
     * @param txHash  签名广播产生的交易哈希
     * @return true 表示确认成功；false 表示该 address 无锁定记录（可能已 Confirm/Cancel）
     */
    public boolean confirmNonce(String address, long nonce, String txHash) {
        boolean removed = lockedNonces.remove(address, nonce);
        if (!removed) {
            log.warn("confirmNonce: no lock record for address={} nonce={} (already confirmed/cancelled?)",
                    address, nonce);
            // 仍尝试写入 pool，保证幂等（Seata Confirm 可能重试）
        }
        try {
            long nextNonce = nonce + 1;
            NonceState nextState = new NonceState(txHash, nextNonce, new Date().getTime(), NonceState.STATUS_USED);
            add(address, nextState);
            log.debug("confirmNonce success: address={} usedNonce={} nextNonce={} txHash={}",
                    address, nonce, nextNonce, txHash);
            return true;
        } catch (IOException e) {
            log.error("confirmNonce failed to persist nonce pool: address={} nonce={}",
                    address, nonce, e);
            return false;
        }
    }

    /**
     * 取消 nonce 锁定（TCC Cancel 阶段调用）——释放锁定，nonce 标记为"可用"。
     *
     * <p>仅从预锁定表移除，不写入 NoncePool（nonce 未被使用，仍可被后续事务获取）。
     * 调用方（SigningTccAction.Cancel）应在全局事务回滚时调用本方法。</p>
     *
     * @param address 钱包地址
     * @param nonce   Try 阶段锁定的 nonce
     * @return true 表示释放成功；false 表示无锁定记录（幂等：可能已 Cancel）
     */
    public boolean cancelNonce(String address, long nonce) {
        boolean removed = lockedNonces.remove(address, nonce);
        if (removed) {
            log.info("cancelNonce success: address={} nonce={} released back to AVAILABLE",
                    address, nonce);
        } else {
            log.warn("cancelNonce: no lock record for address={} nonce={} (already confirmed/cancelled, idempotent)",
                    address, nonce);
        }
        return removed;
    }

    /**
     * 查询当前锁定的 nonce（供测试与可观测性使用）。
     *
     * @param address 钱包地址
     * @return 锁定的 nonce，或 {@code null} 表示无锁定
     */
    public Long getLockedNonce(String address) {
        return lockedNonces.get(address);
    }

    /**
     * 判断该 address 当前是否有锁定 nonce（供测试使用）。
     *
     * @param address 钱包地址
     * @return true 表示有锁定
     */
    public boolean isLocked(String address) {
        return lockedNonces.containsKey(address);
    }
}
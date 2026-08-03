package org.nexus.core.contract;

import jakarta.annotation.PostConstruct;
import org.nexus.db.Leveldb;
import org.nexus.encoding.JSONEncodeDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合约注册表：内存索引（{@link ConcurrentHashMap}）+ LevelDB 持久化双层。
 *
 * <p>参考 {@code PeersStorage} 的双层模式，但落盘策略改为<b>写入即落盘</b>
 * （注册是低频关键操作，无需定时批量）。内存为权威源，LevelDB 为崩溃恢复兜底。</p>
 *
 * <p>LevelDB key 设计：</p>
 * <ul>
 *   <li>{@code contract:index} → JSON 地址数组（启动全量加载清单）</li>
 *   <li>{@code contract:<address>} → {@link RegisteredContract} JSON（单合约元数据）</li>
 * </ul>
 *
 * <p>事务策略：先内存后落盘——内存写成功即对查询可见，落盘失败仅记 WARN 日志
 * （不回滚内存；重启时从 LevelDB 加载，若落盘失败则重启后丢失该条，需重新注册。
 * 注册低频且可重试，可接受）。不引入分布式事务。</p>
 *
 * <p>线程安全：{@link ConcurrentHashMap} 保证并发读 + 单写互斥。
 * {@link #register} 用 {@code synchronized} 串行化以维护 {@code contract:index} 一致性
 * （注册低频，synchronized 可接受）。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class ContractRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ContractRegistry.class);

    private static final String INDEX_KEY = "contract:index";
    private static final String KEY_PREFIX = "contract:";

    private final ConcurrentHashMap<String, RegisteredContract> index = new ConcurrentHashMap<>();

    @Autowired
    private Leveldb leveldb;

    @Autowired
    private JSONEncodeDecoder codec;

    @Value("${nexus.contract.registry-enabled:true}")
    private boolean enabled;

    @Value("${nexus.contract.max-list-size:100}")
    private int maxListSize;

    /**
     * 启动时从 LevelDB 全量加载已注册合约到内存。
     *
     * <p>加载流程：读 {@code contract:index} 得地址数组，逐个读
     * {@code contract:<addr>} 反序列化装入内存。若 {@code registry-enabled=false}
     * 则跳过加载（registry 行为等同空桩）。</p>
     */
    @PostConstruct
    public void init() {
        if (!enabled) {
            logger.info("ContractRegistry disabled (nexus.contract.registry-enabled=false), skip loading");
            return;
        }
        try {
            byte[] indexBytes = leveldb.read(INDEX_KEY.getBytes(StandardCharsets.UTF_8));
            if (indexBytes == null || indexBytes.length == 0) {
                logger.info("ContractRegistry init: no persisted contracts");
                return;
            }
            String[] addresses = codec.decode(indexBytes, String[].class);
            if (addresses == null || addresses.length == 0) {
                logger.info("ContractRegistry init: empty contract index");
                return;
            }
            int loaded = 0;
            for (String addr : addresses) {
                if (addr == null || addr.isEmpty()) continue;
                RegisteredContract rc = loadOne(addr);
                if (rc != null) {
                    index.put(addr, rc);
                    loaded++;
                } else {
                    logger.warn("ContractRegistry init: failed to load contract {} (skipped)", addr);
                }
            }
            logger.info("ContractRegistry init: loaded {} contracts from LevelDB", loaded);
        } catch (Exception e) {
            logger.warn("ContractRegistry init failed: {}", e.getMessage(), e);
        }
    }

    private RegisteredContract loadOne(String address) {
        byte[] data = leveldb.read((KEY_PREFIX + address).getBytes(StandardCharsets.UTF_8));
        if (data == null || data.length == 0) return null;
        return codec.decode(data, RegisteredContract.class);
    }

    /**
     * 注册合约：写入内存 + 即时落盘 LevelDB。
     *
     * <p>若地址已存在，返回 {@code false}（调用方决定是否报错）。落盘失败仅记 WARN，
     * 不回滚内存（最终一致）。</p>
     *
     * @param contract 合约元数据（address 非空且注册表中不存在）
     * @return {@code true} 注册成功；{@code false} 地址已存在
     */
    public synchronized boolean register(RegisteredContract contract) {
        if (contract == null || contract.getAddress() == null) {
            throw new IllegalArgumentException("contract or address is null");
        }
        String addr = contract.getAddress();
        if (index.containsKey(addr)) {
            return false;
        }
        // 先内存：写成功即对查询可见
        index.put(addr, contract);
        // 后落盘：失败仅 WARN，不回滚内存
        try {
            persistOne(contract);
            persistIndex();
        } catch (Exception e) {
            logger.warn("ContractRegistry register: persist failed for {} (in-memory still visible): {}",
                    addr, e.getMessage(), e);
        }
        return true;
    }

    private void persistOne(RegisteredContract contract) {
        byte[] value = codec.encode(contract);
        if (value == null) {
            throw new IllegalStateException("encode contract failed: " + contract.getAddress());
        }
        leveldb.write((KEY_PREFIX + contract.getAddress()).getBytes(StandardCharsets.UTF_8), value);
    }

    private void persistIndex() {
        List<String> addresses = new ArrayList<>(index.keySet());
        byte[] value = codec.encode(addresses.toArray(new String[0]));
        if (value == null) {
            throw new IllegalStateException("encode contract index failed");
        }
        leveldb.write(INDEX_KEY.getBytes(StandardCharsets.UTF_8), value);
    }

    /**
     * 按地址查询合约。
     *
     * @param address 合约地址（hex，0x 前缀）
     * @return 合约元数据；未命中返回 {@code null}
     */
    public RegisteredContract getByAddress(String address) {
        if (address == null) return null;
        return index.get(address);
    }

    /**
     * 列表分页查询，按 {@code createdAt} 倒序。
     *
     * @param offset 起始偏移（&lt; 0 视为 0）
     * @param limit  返回上限（&le; 0 或 &gt; max-list-size 自动夹逼到 [1, max-list-size]）
     * @return 合约列表（快照副本），不会返回 {@code null}
     */
    public List<RegisteredContract> list(int offset, int limit) {
        if (offset < 0) offset = 0;
        if (limit <= 0 || limit > maxListSize) limit = maxListSize;
        List<RegisteredContract> snapshot = new ArrayList<>(index.values());
        snapshot.sort(Comparator.comparingLong(RegisteredContract::getCreatedAt).reversed());
        if (offset >= snapshot.size()) {
            return new ArrayList<>();
        }
        int end = Math.min(offset + limit, snapshot.size());
        return new ArrayList<>(snapshot.subList(offset, end));
    }

    /**
     * 判断地址是否已注册。
     *
     * @param address 合约地址
     * @return {@code true} 已注册
     */
    public boolean exists(String address) {
        if (address == null) return false;
        return index.containsKey(address);
    }

    /**
     * 当前注册表条目数。
     *
     * @return 条目数
     */
    public int size() {
        return index.size();
    }
}
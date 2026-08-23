package org.nexus.db;

import com.google.protobuf.InvalidProtocolBufferException;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.nexus.Start;
import org.nexus.command.IncubatorAddress;
import org.nexus.consensus.pow.ProposersFactory;
import org.nexus.core.Block;
import org.nexus.core.BlocksCache;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.account.Account;
import org.nexus.core.account.AccountDB;
import org.nexus.core.account.Transaction;
import org.nexus.core.event.AccountUpdateFailedEvent;
import org.nexus.core.event.AccountUpdatedEvent;
import org.nexus.core.event.NewBestBlockEvent;
import org.nexus.core.event.NewBlockEvent;
import org.nexus.core.incubator.Incubator;
import org.nexus.core.incubator.IncubatorDB;
import org.nexus.core.incubator.RateTable;
import org.nexus.core.validate.MerkleRule;
import org.nexus.encoding.BigEndian;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.protobuf.tcp.command.HatchModel;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

@Component
public class StateDB implements ApplicationListener<AccountUpdatedEvent> {
    private static final Logger logger = LoggerFactory.getLogger(StateDB.class);
    private static final JSONEncodeDecoder codec = new JSONEncodeDecoder();
    private static final int BLOCKS_PER_UPDATE_LOWER_BOUNDS = 4096;

    // 区块相对于已持久化的账本产生状态变更的账户
    // block hash -> public key hash -> account
    private Map<String, Map<String, AccountState>> cache;

    // 区块的后续确认
    private Map<String, Set<String>> confirms;

    // 最少确认数量
    private Map<String, Integer> leastConfirms;

    // 最新确认的区块
    private Block latestConfirmed;

    // 事务缓存
    // block hash -> transaction hashes
    private Map<String, Set<String>> transactionIndex;

    private static final Base64.Encoder encodeNr = Base64.getEncoder();
    public static final int CACHE_SIZE = 512;

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private AccountDB accountDB;

    @Autowired
    private IncubatorDB incubatorDB;

    @Autowired
    private RateTable rateTable;

    @Autowired
    private MerkleRule merkleRule;

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private Block genesis;

    /**
     * 共识模式（PLAN-005）：pos 模式下 leastConfirms 按活跃验证人数计算，
     * 而非 DPoS proposer 时间表（后者在 PoS 自举单验证人场景导致 confirms
     * 永不达标 → 区块不落 PG → 多节点无法共享链状态）。
     */
    @org.springframework.beans.factory.annotation.Value("${nexus.consensus.mode:pow}")
    private String consensusMode;

    /** 验证人注册表（PoS leastConfirms 计算来源；可选注入，单测/无共识节点为 null）。 */
    @Autowired(required = false)
    private org.nexus.consensus.pos.ValidatorRegistry validatorRegistry;

    public ValidatorStateFactory getValidatorStateFactory() {
        return validatorStateFactory;
    }

    public TargetStateFactory getTargetStateFactory() {
        return targetStateFactory;
    }

    public ProposersFactory getProposersFactory() {
        return proposersFactory;
    }

    @Autowired
    private ValidatorStateFactory validatorStateFactory;

    @Autowired
    private TargetStateFactory targetStateFactory;

    @Autowired
    private ProposersFactory proposersFactory;

    @Value("${nexus.consensus.blocks-per-era}")
    int blocksPerEra;

    private ReadWriteLock readWriteLock;

    // 写入但未确认的区块
    private BlocksCache blocksCache;

    // 正在同步状态到数据库的区块
    private Block pendingBlock;

    @Override
    public void onApplicationEvent(AccountUpdatedEvent event) {
        // null-guard: pendingBlock may be null if a stale event arrives
        if (pendingBlock == null || !Arrays.equals(event.getBlock().getHash(), pendingBlock.getHash())) {
            return;
        }
        {
            // 接收到状态更新完成事件后，将这个区块标记为状态已更新完成
            // 清除缓存
            blocksCache.getAll()
                    .stream().filter(b -> b.nHeight <= pendingBlock.nHeight
                    && !Arrays.equals(b.getHash(), pendingBlock.getHash()))
                    .map(b -> {
                        List<Block> toDelete = blocksCache.getDescendantBlocks(b);
                        toDelete.add(b);
                        return toDelete;
                    })
                    .forEach(blocks -> blocks.forEach(this::deleteCache));
            deleteCache(pendingBlock);
            latestConfirmed = pendingBlock;
            pendingBlock = null;
            logger.info("update account at height " + event.getBlock().nHeight + " to db success");
        }
    }

    public void deleteCache(Block b) {
        blocksCache.deleteBlock(b);
        cache.remove(b.getHashHexString());
        confirms.remove(b.getHashHexString());
        leastConfirms.remove(b.getHashHexString());
        transactionIndex.remove(b.getHashHexString());
    }

    /**
     * 分叉回滚（PLAN-003）：从本地最佳块沿父链回滚到目标高度（含），
     * 清理 blocksCache 与全部索引。回滚后 best 回到分叉点。
     *
     * <p>仅回滚内存缓存层（LevelDB/PG 持久化的 header 记录由链切换
     * 写入长链时以最新为准覆盖）；供 {@code ReorgManager} 在采纳更长分叉链前调用。</p>
     *
     * @param targetHeight 回滚目标高度（低于该高度的块保留）
     * @return 实际回滚的区块数
     */
    public int rollbackTo(long targetHeight) {
        this.readWriteLock.writeLock().lock();
        try {
            int rolledBack = 0;
            Block cur = getBestBlock();
            while (cur != null && cur.nHeight > targetHeight) {
                Block parent = getBlock(cur.hashPrevBlock);
                deleteCache(cur);
                cur = parent;
                rolledBack++;
            }
            if (rolledBack > 0) {
                // best 引用回退到分叉点（或 genesis）
                if (cur != null) {
                    latestConfirmed = cur;
                }
                logger.info("rollbackTo({}): rolled back {} blocks, best now height {}",
                        targetHeight, rolledBack, cur == null ? 0 : cur.nHeight);
            }
            return rolledBack;
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }

    public StateDB(
            @Value("${nexus.consensus.blocks-per-era}") int blocksPerEra,
            @Value("${nexus.allow-miner-joins-era}") int allowMinersJoinEra,
            @Value("${nexus.consensus.block-interval}") int blockInterval,
            @Value("${nexus.block-interval-switch-era}") long blockIntervalSwitchEra,
            @Value("${nexus.block-interval-switch-to}") int blockIntervalSwitchTo
    ) {
        this.readWriteLock = new ReentrantReadWriteLock();
        this.cache = new ConcurrentLinkedHashMap.Builder<String, Map<String, AccountState>>()
                .maximumWeightedCapacity(CACHE_SIZE).build();
        this.blocksCache = new BlocksCache(CACHE_SIZE);
        this.transactionIndex = new HashMap<>();
        this.confirms = new HashMap<>();
        this.leastConfirms = new HashMap<>();

        if (allowMinersJoinEra < 0) {
            logger.info("miners join is disabled");
        } else {
            logger.info("miners join is enabled, allow miners join at height " + (allowMinersJoinEra * blocksPerEra + 1));
        }
        logger.info("initial block interval is " + blockInterval);
        if (blockIntervalSwitchEra >= 0) {
            logger.info("switch block interval to " + blockIntervalSwitchTo + " at height " + (blockIntervalSwitchEra * blocksPerEra + 1));
        }
    }

    @PostConstruct
    public void init() {
        readWriteLock.writeLock().lock();
        try {
            initUnsafe();
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    private void initUnsafe() {
        proposersFactory.setStateDB(this);
        validatorStateFactory.setStateDB(this);
        targetStateFactory.setStateDB(this);

        this.latestConfirmed = bc.getLastConfirmedBlock();
        Block last = genesis;
        int blocksPerUpdate = 0;
        while (blocksPerUpdate < BLOCKS_PER_UPDATE_LOWER_BOUNDS) {
            blocksPerUpdate += blocksPerEra;
        }
        while (true) {
            List<Block> blocks = bc.getCanonicalBlocks(last.nHeight + 1, blocksPerUpdate);
            int size = blocks.size();
            if (size < blocksPerEra) {
                break;
            }
            if (Start.ENABLE_ASSERTION) {
                Assert.isTrue(Arrays.equals(last.getHash(), blocks.get(0).hashPrevBlock)
                        && isChain(blocks), "get blocks from database failed"
                );
            }
            while (blocks.size() >= blocksPerEra) {
                validatorStateFactory.initCache(last, blocks.subList(0, blocksPerEra));
                targetStateFactory.initCache(last, blocks.subList(0, blocksPerEra));
                proposersFactory.initCache(last, blocks.subList(0, blocksPerEra));
                last = blocks.get(blocksPerEra - 1);
                blocks = blocks.subList(blocksPerEra, blocks.size());
            }
        }
    }

    public Block getBestBlock() {
        this.readWriteLock.readLock().lock();
        try {
            return getBestBlockUnsafe();
        } finally {
            this.readWriteLock.readLock().unlock();
        }
    }

    private Block getBestBlockUnsafe() {
        return blocksCache.getLeaves().stream()
                .max((a, b) -> {
                    if (a.nHeight != b.nHeight) {
                        return Long.compare(a.nHeight, b.nHeight);
                    }
                    // pow 更小的占优势
                    return -BigEndian.decodeUint256(Block.calculatePOWHash(a))
                            .compareTo(
                                    BigEndian.decodeUint256(Block.calculatePOWHash(b))
                            );
                })
                .orElse(this.latestConfirmed);
    }

    private Block getHeaderUnsafe(byte[] hash) {
        if (Arrays.equals(latestConfirmed.getHash(), hash)) {
            return this.latestConfirmed;
        }
        return Optional.ofNullable(blocksCache.getBlock(hash))
                .orElseGet(() -> bc.getHeader(hash));
    }

    public Block getHeader(byte[] hash) {
        this.readWriteLock.readLock().lock();
        try {
            return getHeaderUnsafe(hash);
        } finally {
            this.readWriteLock.readLock().unlock();
        }
    }

    public Block findAncestorHeader(byte[] hash, long height) {
        this.readWriteLock.readLock().lock();
        try {
            Block bHeader = getHeaderUnsafe(hash);
            if (bHeader.nHeight < height) {
                return null;
            }
            while (bHeader != null && bHeader.nHeight != height) {
                bHeader = getHeaderUnsafe(bHeader.hashPrevBlock);
            }
            return bHeader;
        } finally {
            this.readWriteLock.readLock().unlock();
        }

    }

    public List<Block> getAncestorBlocks(byte[] bhash, long anum) {
        this.readWriteLock.readLock().lock();
        try {
            Block b = blocksCache.getBlock(bhash);
            if (Arrays.equals(bhash, this.latestConfirmed.getHash())) {
                b = this.latestConfirmed;
            }
            if (b == null) {
                return bc.getAncestorBlocks(bhash, anum);
            }
            BlocksCache res = new BlocksCache();
            List<Block> blocks = blocksCache.getAncestors(b)
                    .stream().filter(bl -> bl.nHeight >= anum).collect(toList());
            res.addBlocks(blocks);
            res.addBlocks(bc.getAncestorBlocks(res.getAll().get(0).hashPrevBlock, anum));
            List<Block> all = res.getAll();

            if (Start.ENABLE_ASSERTION) {
                Assert.isTrue(all.size() == (b.nHeight - anum + 1) &&
                        all.get(0).nHeight == anum &&
                        isChain(all), "get ancestors failed"
                );
            }
            return all;
        } finally {
            this.readWriteLock.readLock().unlock();
        }
    }

    public static boolean isChain(List<Block> blocks) {
        for (int i = 0; i < blocks.size() - 1; i++) {
            byte[] h1 = blocks.get(i).getHash();
            byte[] h2 = blocks.get(i + 1).hashPrevBlock;
            if (!Arrays.equals(h1, h2)) {
                return false;
            }
        }
        return true;
    }

    public boolean hasBlockInCache(byte[] hash) {
        this.readWriteLock.readLock().lock();
        try {
            return blocksCache.hasBlock(hash);
        } finally {
            this.readWriteLock.readLock().unlock();
        }
    }

    public Block getBlock(byte[] hash) {
        this.readWriteLock.readLock().lock();
        try {
            if (Arrays.equals(latestConfirmed.getHash(), hash)) {
                return this.latestConfirmed;
            }
            return Optional.ofNullable(blocksCache.getBlock(hash))
                    .orElseGet(() -> bc.getBlock(hash));
        } finally {
            this.readWriteLock.readLock().unlock();
        }

    }

    public boolean hasBlock(byte[] hash) {
        readWriteLock.readLock().lock();
        try {
            return blocksCache.hasBlock(hash) ||
                    Arrays.equals(latestConfirmed.getHash(), hash) ||
                    bc.hasBlock(hash);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    public boolean hasTransaction(byte[] blockHash, byte[] transactionHash) {
        readWriteLock.readLock().lock();
        try {
            return hasTransactionUnsafe(blockHash, transactionHash);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    private boolean hasTransactionUnsafe(byte[] blockHash, byte[] transactionHash) {
        if (Arrays.equals(latestConfirmed.getHash(), blockHash)) {
            return bc.hasTransaction(transactionHash);
        }
        String key = Hex.encodeHexString(blockHash);
        Block b = blocksCache.getBlock(blockHash);
        if (b == null) {
            return true;
        }
        if (transactionIndex.containsKey(key) && transactionIndex.get(key).contains(Hex.encodeHexString(transactionHash))) {
            return true;
        }
        return hasTransactionUnsafe(b.hashPrevBlock, transactionHash);
    }

    public Transaction getTransaction(byte[] blockHash, byte[] txHash) {
        readWriteLock.readLock().lock();
        try {
            return getTransactionUnsafe(blockHash, txHash);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    private Transaction getTransactionUnsafe(byte[] blockHash, byte[] txHash) {
        if (Arrays.equals(latestConfirmed.getHash(), blockHash)) {
            return bc.getTransaction(txHash);
        }
        Block b = blocksCache.getBlock(blockHash);
        if (b == null || b.body == null) {
            return null;
        }
        for (Transaction t : b.body) {
            if (Arrays.equals(t.getHash(), txHash)) {
                t.height = b.nHeight;
                return t;
            }
        }
        return getTransactionUnsafe(b.hashPrevBlock, txHash);
    }

    public boolean hasPayload(byte[] hash, int type, byte[] payload) {
        readWriteLock.readLock().lock();
        try {
            return hasPayloadUnsafe(hash, type, payload);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    private boolean hasPayloadUnsafe(byte[] blockHash, int type, byte[] payload) {
        if (Arrays.equals(latestConfirmed.getHash(), blockHash)) {
            return bc.hasPayload(type, payload);
        }
        Block b = blocksCache.getBlock(blockHash);
        if (b == null) {
            return true;
        }
        for (Transaction t : b.body) {
            if (t.payload != null && t.type == type && Arrays.equals(t.payload, payload)) {
                return true;
            }
        }
        return hasPayloadUnsafe(b.hashPrevBlock, type, payload);
    }

    /**
     * 计算区块确认所需的最小确认数（PLAN-005）：
     * <ul>
     *   <li><b>pos 模式</b>：按活跃验证人数 × 2/3，至少 1——自举单验证人即可确认，
     *       解决 PoS 下区块永不落 PG（共享链状态失效）问题</li>
     *   <li><b>pow/dpos 模式</b>：按 DPoS proposer 时间表（原语义）</li>
     * </ul>
     */
    private int computeLeastConfirms(Block block) {
        if ("pos".equalsIgnoreCase(consensusMode)) {
            int active = 0;
            if (validatorRegistry != null) {
                java.util.List<org.nexus.consensus.pos.Validator> vals = validatorRegistry.getActiveValidators();
                if (vals != null) {
                    active = vals.size();
                }
            }
            return Math.max(1, (int) Math.ceil(active * 2.0 / 3));
        }
        return (int) Math.ceil(
                proposersFactory.getProposers(getBlock(block.hashPrevBlock)).size() * 2.0 / 3);
    }

    public Block getLastConfirmed() {
        return latestConfirmed;
    }

    // 写入区块
    public void writeBlock(Block block) {
        this.readWriteLock.writeLock().lock();
        try {
            // 这个区块所在高度已经被确认了
            if (block.nHeight <= latestConfirmed.nHeight) {
                return;
            }
            // 判断是否是孤块
            if (!Arrays.equals(this.latestConfirmed.getHash(), block.hashPrevBlock) && !blocksCache.hasBlock(block.hashPrevBlock)) {
                return;
            }
            // 已经写入过的区块
            if (blocksCache.hasBlock(block.getHash())) {
                return;
            }
            blocksCache.addBlock(block);

            // 写入事务索引
            if (!transactionIndex.containsKey(block.getHashHexString())) {
                transactionIndex.put(block.getHashHexString(), new HashSet<>());
            }
            block.body.forEach(t -> {
                t.height = block.nHeight;
                t.blockHash = block.getHash();
                transactionIndex.get(block.getHashHexString()).add(t.getHashHexString());
            });

            leastConfirms.put(block.getHashHexString(), computeLeastConfirms(block));
            List<Block> ancestors = blocksCache.getAncestors(block);
            for (Block b : ancestors) {
                if (!confirms.containsKey(b.getHashHexString())) {
                    confirms.put(b.getHashHexString(), new HashSet<>());
                }
                // 区块不能确认自己
                if (Arrays.equals(b.getHash(), block.getHash())) {
                    continue;
                }
                confirms.get(b.getHashHexString()).add(Hex.encodeHexString(block.body.get(0).to));
            }
            Collections.reverse(ancestors);

            // 试图查找被确认的区块，找到则更新到 db
            List<Block> confirmedAncestors = new ArrayList<>();
            for (int i = 0; i < ancestors.size(); i++) {
                Block b = ancestors.get(i);
                if (confirms.get(b.getHashHexString()).size() < leastConfirms.get(b.getHashHexString())) {
                    continue;
                }
                if (block.nHeight - b.nHeight < 3) {
                    continue;
                }
                // 发现有可以确认的区块
                confirmedAncestors = ancestors.subList(i, ancestors.size());
                Collections.reverse(confirmedAncestors);
                break;
            }

            if (confirmedAncestors.size() == 0) {
                return;
            }

            // 更新到 db
            for (int i = 0; i < confirmedAncestors.size(); ) {
                Block b = confirmedAncestors.get(i);
                // CAS 锁，等待上一个区块状态更新成功（带超时，防死锁）
                long waitDeadline = System.currentTimeMillis() + 60_000L;
                while (pendingBlock != null) {
                    if (System.currentTimeMillis() > waitDeadline) {
                        logger.error("Timed out waiting for pendingBlock state update at height "
                                + pendingBlock.nHeight + " — clearing to avoid deadlock.");
                        pendingBlock = null;
                        break;
                    }
                    try { Thread.sleep(100L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    logger.info("wait for account at" + new String(codec.encodeBlock(pendingBlock)) + " updated...");
                }
                boolean writeResult = bc.writeBlock(b);
                if (!writeResult) {
                    // 数据库 写入失败 重试写入
                    logger.error("write block " + new String(codec.encodeBlock(b)) + " to database failed, retrying...");
                    continue;
                }
                logger.info("write block at height " + b.nHeight + " to db success");
                pendingBlock = b;
                ctx.publishEvent(new NewBlockEvent(this, b));
                ctx.publishEvent(new NewBestBlockEvent(this, b));
                i++;
            }
            long finalWaitDeadline = System.currentTimeMillis() + 60_000L;
            while (pendingBlock != null) {
                if (System.currentTimeMillis() > finalWaitDeadline) {
                    logger.error("Timed out waiting for final pendingBlock state update at height "
                            + pendingBlock.nHeight + " — clearing to avoid deadlock.");
                    pendingBlock = null;
                    break;
                }
                try { Thread.sleep(100L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                logger.info("wait for account at" + new String(codec.encodeBlock(pendingBlock)) + " updated...");
            }
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }

    // 获取包含未确认的区块
    public List<Block> getBlocks(long startHeight, long stopHeight, int sizeLimit, boolean clipInitial) {
        if (sizeLimit == 0 || startHeight > stopHeight) {
            return new ArrayList<>();
        }
        this.readWriteLock.readLock().lock();
        try {
            BlocksCache c = new BlocksCache();
            // 从数据库获取一部分
            if (startHeight < latestConfirmed.nHeight) {
                c.addBlocks(bc.getBlocks(startHeight, stopHeight, sizeLimit, clipInitial));
            }
            List<Block> blocks = blocksCache.getAll();
            blocks.add(latestConfirmed);

            // 从 forkdb 获取一部分
            blocks.stream().filter((b) -> b.nHeight >= startHeight && b.nHeight <= stopHeight)
                    .forEach(c::addBlock);

            // 按需进行裁剪
            List<Block> all = c.getAll();
            if (sizeLimit > all.size() || sizeLimit < 0) {
                sizeLimit = all.size();
            }
            if (clipInitial) {
                return all.subList(all.size() - sizeLimit, all.size());
            }
            return all.subList(0, sizeLimit);
        } finally {
            this.readWriteLock.readLock().unlock();
        }
    }

    public AccountState getAccount(byte[] blockHash, byte[] publicKeyHash) {
        readWriteLock.readLock().lock();
        try {
            return getAccountUnsafe(blockHash, publicKeyHash);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    private Map<String, AccountState> getAccountsUnsafe(byte[] blockHash, Collection<byte[]> publicKeyHashes) {
        Map<String, AccountState> res = new HashMap<>();
        for (byte[] h : publicKeyHashes) {
            AccountState account = getAccountUnsafe(blockHash, h);
            if (account == null) {
                return null;
            }
            res.put(Hex.encodeHexString(h), account);
        }
        return res;
    }

    public Map<String, AccountState> getAccounts(byte[] blockHash, Collection<byte[]> publicKeyHashes) {
        readWriteLock.writeLock().lock();
        try {
            return getAccountsUnsafe(blockHash, publicKeyHashes);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    // 获取到某一区块（包含该区块)的某个账户的状态，用于对后续区块的事务进行验证
    private AccountState getAccountUnsafe(byte[] blockHash, byte[] publicKeyHash) {

        if (Arrays.equals(blockHash, latestConfirmed.getHash())) {
            return getAccount(publicKeyHash);
        }
        Block header = blocksCache.getBlock(blockHash);
        if (header == null || header.nHeight < latestConfirmed.nHeight) {
            return null;
        }
        // 判断新的区块是否在 main fork 上面;
//        Block ancestor = blocksCache.getAncestor(header, latestConfirmed.nHeight);
//        if (ancestor == null || !Arrays.equals(latestConfirmed.getHash(), ancestor.getHash())) {
//            return null;
//        }
        // 判断是否在缓存中
        String blockKey = Hex.encodeHexString(blockHash);
        String accountKey = Hex.encodeHexString(publicKeyHash);
        if (cache.containsKey(blockKey) && cache.get(blockKey).containsKey(accountKey)) {
            return cache.get(blockKey).get(accountKey).copy();
        }
        // 如果缓存不存在则进行回溯
        AccountState account = getAccountUnsafe(header.hashPrevBlock, publicKeyHash);
        if (account == null) {
            return null;
        }
        Block block = blocksCache.getBlock(blockHash);
        // 把这个区块的事务应用到上一个区块获取的 account，生成新的 account
        for (Transaction tx : block.body) {
            tx.height = block.nHeight;
        }
        AccountState res = applyTransactions(block.body, account.copy());
        if (!cache.containsKey(blockKey)) {
            cache.put(blockKey, new ConcurrentHashMap<>());
        }
        cache.get(blockKey).put(accountKey, res);
        return res.copy();
    }

    // 获取已经持久化的账户
    private AccountState getAccount(byte[] publicKeyHash) {
        Optional<Account> account = accountDB.hasAccount(publicKeyHash);
        AccountState accountState = account.map(x -> {
            AccountState accountState1 = new AccountState();
            accountState1.setAccount(x);
            return accountState1;
        }).orElse(new AccountState(publicKeyHash));

        List<Incubator> incubatorList = incubatorDB.selectList(publicKeyHash);
        Map<String, Incubator> inester = new HashMap<>();
        if (incubatorList.size() > 0) {
            inester = incubatorList.stream().collect(toMap(i -> Hex.encodeHexString(i.getTxid_issue()), i -> i));
        }
        accountState.setInterestMap(inester);
        List<Incubator> shareList = incubatorDB.selectShareList(publicKeyHash);
        Map<String, Incubator> share = new HashMap<>();
        if (shareList.size() > 0) {
            share = shareList.stream().collect(toMap(i -> Hex.encodeHexString(i.getTxid_issue()), i -> i));
        }
        accountState.setShareMap(share);
        return accountState;
    }

    public List<Block> getAll() {
        readWriteLock.readLock().lock();
        try {
            return blocksCache.getAll();
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    public long AverageFee(){
        List<Block> list=getBestChain(10);
        List<Transaction> transactionList=new ArrayList<>();
        list.stream().map(m->m.body).filter(f->f!=null).forEach(f->transactionList.addAll(f));
        Set<Long> longSet=transactionList.stream()
                .filter(transaction -> transaction.type!=Transaction.Type.COINBASE.ordinal())
                .map(Transaction::getFee).collect(toSet());
        if(longSet.size()==0){
            return 0;
        }
        long total=longSet.stream().reduce(Long::sum).orElse(0L);
        return total/longSet.size();
    }

    // get the best chain of forkdb
    public List<Block> getBestChain(int limit) {
        readWriteLock.readLock().lock();
        try {
            List<Block> blocks = blocksCache.getAllForks().get(0);
            if (blocks.size() >= limit) return blocks.subList(0, limit);
            long toFetch = limit- blocks.size();
            List<Block> fetched = bc.getHeaders(blocks.get(0).nHeight - toFetch, (int) toFetch);
            fetched.addAll(blocks);
            return fetched;
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    // count blocks after timestamp
    public long countBlocksAfter(long timestamp) {
        readWriteLock.readLock().lock();
        try {
            return blocksCache.getAll().stream().filter(x -> x.nTime >= timestamp).count() +
                    bc.countBlocksAfter(timestamp);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    // average blocks interval
    public double averageBlocksInterval() {
        readWriteLock.readLock().lock();
        try {
            List<Block> best = blocksCache.getAllForks().get(0);
            if (best.size() >= 10) {
                best = best.subList(0, 10);
                BigDecimal bd = new BigDecimal((best.get(best.size() - 1).nTime - best.get(0).nTime) / (9.0));
                return bd.setScale(2,BigDecimal.ROUND_HALF_UP).doubleValue();
            }
            long toFetch = 10 - best.size();
            List<Block> fetched = bc.getHeaders(best.get(0).nHeight - toFetch, (int) toFetch);
            BigDecimal bd = new BigDecimal((best.get(best.size() - 1).nTime - fetched.get(0).nTime) / (9.0));
            return bd.setScale(2,BigDecimal.ROUND_HALF_UP).doubleValue();
        } finally {
            readWriteLock.readLock().unlock();
        }
    }


    private AccountState applyCoinbase(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        // 如果该账户不是 coinbase 地址退出
        if (!Arrays.equals(tx.to, account.getPubkeyHash())) {
            return accountState;
        }
        long balance = account.getBalance();
        balance += tx.amount;
        account.setBalance(balance);
//        account.setNonce(tx.nonce);
        account.setBlockHeight(tx.height);
        accountState.setAccount(account);
        return accountState;
    }

    private AccountState applyTransfer(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        long balance;
        if (Arrays.equals(RipemdUtility.ripemd160(SHA3Utility.keccak256(tx.from)), account.getPubkeyHash())) {
            balance = account.getBalance();
            balance -= tx.amount;
            balance -= tx.getFee();
            account.setBalance(balance);
            account.setNonce(tx.nonce);
            account.setBlockHeight(tx.height);
            accountState.setAccount(account);
        }
        if (Arrays.equals(tx.to, account.getPubkeyHash())) {
            balance = account.getBalance();
            balance += tx.amount;
            account.setBalance(balance);
            account.setBlockHeight(tx.height);
            accountState.setAccount(account);
        }
        return accountState;
    }

    private AccountState applyIncubate(Transaction tx, AccountState accountState) throws InvalidProtocolBufferException, DecoderException {
        Account account = accountState.getAccount();
        HatchModel.Payload payloadproto = HatchModel.Payload.parseFrom(tx.payload);
        int days = payloadproto.getType();
        String sharpub = payloadproto.getSharePubkeyHash();
        long balance;
        if (Arrays.equals(tx.to, account.getPubkeyHash())) {
            balance = account.getBalance();
            balance -= tx.getFee();
            balance -= tx.amount;
            long incub = account.getIncubatecost();
            incub += tx.amount;
            account.setBalance(balance);
            account.setIncubatecost(incub);
            account.setNonce(tx.nonce);
            account.setBlockHeight(tx.height);
            Incubator incubator = new Incubator(tx.to, tx.getHash(), tx.height, tx.amount, tx.getInterest(tx.height, rateTable, days), tx.height, days);
            Map<String, Incubator> maps = accountState.getInterestMap();
            maps.put(Hex.encodeHexString(tx.getHash()), incubator);
            accountState.setInterestMap(maps);
            accountState.setAccount(account);
        }
        if (sharpub != null && !sharpub.equals("")) {
            byte[] sharepublic = Hex.decodeHex(sharpub.toCharArray());
            if (Arrays.equals(sharepublic, account.getPubkeyHash())) {
                Incubator share = new Incubator(sharepublic, tx.getHash(), tx.height, tx.amount, days, tx.getShare(tx.height, rateTable, days), tx.height);
                Map<String, Incubator> sharemaps = accountState.getShareMap();
                sharemaps.put(Hex.encodeHexString(tx.getHash()), share);
                accountState.setShareMap(sharemaps);
            }
        }
        if (Arrays.equals(IncubatorAddress.resultpubhash(), account.getPubkeyHash())) {
            balance = account.getBalance();
            balance -= tx.amount;
            long nonce = account.getNonce();
            nonce++;
            account.setBalance(balance);
            account.setNonce(nonce);
            account.setBlockHeight(tx.height);
            accountState.setAccount(account);
        }
        return accountState;
    }

    private AccountState applyExtractInterest(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        long balance;
        if (!Arrays.equals(tx.to, account.getPubkeyHash())) {
            return accountState;
        }
        balance = account.getBalance();
        balance -= tx.getFee();
        balance += tx.amount;
        account.setBalance(balance);
        account.setNonce(tx.nonce);
        account.setBlockHeight(tx.height);
        accountState.setAccount(account);

        Map<String, Incubator> map = accountState.getInterestMap();
        Incubator incubator = map.get(Hex.encodeHexString(tx.payload));
        if (incubator == null) {
            logger.info("Interest payload:" + Hex.encodeHexString(tx.payload) + "--->tx:" + tx.getHashHexString());
            return accountState;
        }
        incubator = merkleRule.UpdateExtIncuator(tx, tx.height, incubator);
        map.put(Hex.encodeHexString(tx.payload), incubator);
        accountState.setInterestMap(map);
        return accountState;
    }

    private AccountState applyExtractSharingProfit(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        if (!Arrays.equals(tx.to, account.getPubkeyHash())) {
            return accountState;
        }
        long balance = account.getBalance();
        balance -= tx.getFee();
        balance += tx.amount;
        account.setBalance(balance);
        account.setNonce(tx.nonce);
        account.setBlockHeight(tx.height);
        accountState.setAccount(account);

        Map<String, Incubator> map = accountState.getShareMap();
        Incubator incubator = map.get(Hex.encodeHexString(tx.payload));
        if (incubator == null) {
            logger.info("Share payload:" + Hex.encodeHexString(tx.payload) + "--->tx:" + tx.getHashHexString());
            return accountState;
        }
        incubator = merkleRule.UpdateExtIncuator(tx, tx.height, incubator);
        map.put(Hex.encodeHexString(tx.payload), incubator);
        accountState.setShareMap(map);
        return accountState;
    }

    // 调用前先执行深拷贝
    public AccountState applyTransaction(Transaction tx, AccountState accountState) throws Exception {
        int type = tx.type;
        switch (type) {
            case 0x00:
                return applyCoinbase(tx, accountState);
            case 0x01:
                return applyTransfer(tx, accountState);
            case 0x02:
                return applyVote(tx, accountState);
            case 0x03:
                return applyDeposit(tx, accountState);
            case 0x09:
                return applyIncubate(tx, accountState);
            case 0x0a:
                return applyExtractInterest(tx, accountState);
            case 0x0b:
                return applyExtractSharingProfit(tx, accountState);
            case 0x0c:
                return applyExtractCost(tx, accountState);
            case 0x0d:
                return applyCancelVote(tx, accountState);
            case 0x0e:
                return applyMortgage(tx, accountState);
            case 0x0f:
                return applyCancelMortgage(tx, accountState);
            default:
                throw new Exception("unsupported transaction type: " + Transaction.Type.values()[type].toString());
        }
    }

    private AccountState applyExtractCost(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        if (!Arrays.equals(tx.to, account.getPubkeyHash())) {
            return accountState;
        }
        long balance = account.getBalance();
        balance -= tx.getFee();
        balance += tx.amount;
        long incub = account.getIncubatecost();
        incub -= tx.amount;
        account.setBalance(balance);
        account.setIncubatecost(incub);
        account.setNonce(tx.nonce);
        account.setBlockHeight(tx.height);
        accountState.setAccount(account);

        Map<String, Incubator> map = accountState.getInterestMap();
        Incubator incubator = map.get(Hex.encodeHexString(tx.payload));
        if (incubator == null) {
            logger.info("Cost payload:" + Hex.encodeHexString(tx.payload) + "--->tx:" + tx.getHashHexString());
            return accountState;
        }
        incubator.setCost(0);
        incubator.setHeight(tx.height);
        map.put(Hex.encodeHexString(tx.payload), incubator);
        accountState.setInterestMap(map);
        return accountState;
    }

    private AccountState applyDeposit(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        if (!Arrays.equals(tx.to, account.getPubkeyHash())) {
            return accountState;
        }
        long balance = account.getBalance();
        balance -= tx.getFee();
        account.setBalance(balance);
        account.setNonce(tx.nonce);
        account.setBlockHeight(tx.height);
        accountState.setAccount(account);
        return accountState;
    }

    private AccountState applyVote(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        long balance;
        if (Arrays.equals(RipemdUtility.ripemd160(SHA3Utility.keccak256(tx.from)), account.getPubkeyHash())) {
            balance = account.getBalance();
            balance -= tx.amount;
            balance -= tx.getFee();
            account.setBalance(balance);
            account.setNonce(tx.nonce);
            account.setBlockHeight(tx.height);
            accountState.setAccount(account);
        }
        if (Arrays.equals(tx.to, account.getPubkeyHash())) {
            long vote = account.getVote();
            vote += tx.amount;
            account.setVote(vote);
            account.setBlockHeight(tx.height);
            accountState.setAccount(account);
        }
        return accountState;
    }

    private AccountState applyCancelVote(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        long balance;
        if (Arrays.equals(RipemdUtility.ripemd160(SHA3Utility.keccak256(tx.from)), account.getPubkeyHash())) {
            balance = account.getBalance();
            balance += tx.amount;
            balance -= tx.getFee();
            account.setBalance(balance);
            account.setNonce(tx.nonce);
            account.setBlockHeight(tx.height);
            accountState.setAccount(account);
        }
        if (Arrays.equals(tx.to, account.getPubkeyHash())) {
            long vote = account.getVote();
            vote -= tx.amount;
            account.setVote(vote);
            account.setBlockHeight(tx.height);
            accountState.setAccount(account);
        }
        return accountState;
    }

    public AccountState applyMortgage(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        if (!Arrays.equals(tx.to, account.getPubkeyHash())) {
            return accountState;
        }
        long balance = account.getBalance();
        balance -= tx.getFee();
        balance -= tx.amount;
        long mortgage = account.getMortgage();
        mortgage += tx.amount;
        account.setBalance(balance);
        account.setMortgage(mortgage);
        account.setNonce(tx.nonce);
        account.setBlockHeight(tx.height);
        accountState.setAccount(account);
        return accountState;
    }

    private AccountState applyCancelMortgage(Transaction tx, AccountState accountState) {
        Account account = accountState.getAccount();
        if (!Arrays.equals(tx.to, account.getPubkeyHash())) {
            return accountState;
        }
        long balance = account.getBalance();
        balance -= tx.getFee();
        long mortgage = account.getMortgage();
        mortgage -= tx.amount;
        account.setBalance(balance);
        account.setMortgage(mortgage);
        account.setNonce(tx.nonce);
        account.setBlockHeight(tx.height);
        accountState.setAccount(account);
        return accountState;
    }

    public AccountState applyTransactions(List<Transaction> txs, AccountState account) {
        for (Transaction transaction : txs) {
            try {
                if (account == null) {
                    return null;
                }
                account = applyTransaction(transaction, account);
            } catch (Exception e) {
                logger.error("applyTransaction failed, aborting batch", e);
                return null;
            }

        }
        return account;
    }

    public List<Transaction> getTransactionsByTo(byte[] publicKeyHash, int offset, int limit) {
        readWriteLock.readLock().lock();
        try {
            Block best = getBestBlockUnsafe();
            return getTransactionsByTo(best.getHash(), publicKeyHash, offset, limit).stream().limit(limit).collect(toList());
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    public List<Transaction> getTransactionsByFrom(byte[] publicKey, int offset, int limit) {
        readWriteLock.readLock().lock();
        try {
            Block best = getBestBlockUnsafe();
            return getTransactionsByFrom(best.getHash(), publicKey, offset, limit).stream().limit(limit).collect(toList());
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    public List<Transaction> getTransactionsByFromAndTo(byte[] from, byte[] to, int offset, int limit) {
        readWriteLock.readLock().lock();
        try {
            Block best = getBestBlockUnsafe();
            return getTransactionsByFromAndTo(best.getHash(), from, to, offset, limit).stream().limit(limit).collect(toList());
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    public List<Transaction> getTransactionsByToAndType(int type, byte[] publicKeyHash, int offset, int limit) {
        readWriteLock.readLock().lock();
        try {
            Block best = getBestBlockUnsafe();
            return getTransactionsByToAndType(type, best.getHash(), publicKeyHash, offset, limit).stream().limit(limit).collect(toList());
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    public List<Transaction> getTransactionsByFromAndType(int type, byte[] publicKey, int offset, int limit) {
        readWriteLock.readLock().lock();
        try {
            Block best = getBestBlockUnsafe();
            return getTransactionsByFromAndType(type, best.getHash(), publicKey, offset, limit).stream().limit(limit).collect(toList());
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    public List<Transaction> getTransactionsByFromToAndType(int type, byte[] from, byte[] to, int offset, int limit) {
        readWriteLock.readLock().lock();
        try {
            Block best = getBestBlockUnsafe();
            return getTransactionsByFromToAndType(type, best.getHash(), from, to, offset, limit).stream().limit(limit).collect(toList());
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    private List<Transaction> getTransactionsByTo(byte[] blockHash, byte[] publicKeyHash, int offset, int limit) {
        if (Arrays.equals(blockHash, latestConfirmed.getHash())) {
            return bc.getTransactionsByTo(publicKeyHash, offset, limit);
        }
        Block b = blocksCache.getBlock(blockHash);
        if (b == null) {
            return new ArrayList<>();
        }
        if (b.body == null) {
            return getTransactionsByTo(b.hashPrevBlock, publicKeyHash, offset, limit);
        }
        List<Transaction> transactions = b.body.stream().filter(tx -> Arrays.equals(tx.to, publicKeyHash)).collect(toList());
        List<Transaction> transactionsPrevBlocks = getTransactionsByTo(b.hashPrevBlock, publicKeyHash, offset, limit);
        transactionsPrevBlocks.addAll(transactions);
        return transactionsPrevBlocks;
    }

    private List<Transaction> getTransactionsByFrom(byte[] blockHash, byte[] publicKey, int offset, int limit) {
        if (Arrays.equals(blockHash, latestConfirmed.getHash())) {
            return bc.getTransactionsByFrom(publicKey, offset, limit);
        }
        Block b = blocksCache.getBlock(blockHash);
        if (b == null) {
            return new ArrayList<>();
        }
        if (b.body == null) {
            return getTransactionsByFrom(b.hashPrevBlock, publicKey, offset, limit);
        }
        List<Transaction> transactions = b.body.stream().filter(tx -> Arrays.equals(tx.from, publicKey)).collect(toList());
        List<Transaction> transactionsPrevBlocks = getTransactionsByFrom(b.hashPrevBlock, publicKey, offset, limit);
        transactionsPrevBlocks.addAll(transactions);
        return transactionsPrevBlocks;
    }

    private List<Transaction> getTransactionsByFromAndTo(byte[] blockHash, byte[] from, byte[] to, int offset, int limit) {
        if (Arrays.equals(blockHash, latestConfirmed.getHash())) {
            return bc.getTransactionsByFromAndTo(from, to, offset, limit);
        }
        Block b = blocksCache.getBlock(blockHash);
        if (b == null) {
            return new ArrayList<>();
        }
        if (b.body == null) {
            return getTransactionsByFromAndTo(b.hashPrevBlock, from, to, offset, limit);
        }
        List<Transaction> transactions = b.body.stream().filter(tx -> Arrays.equals(tx.from, from) && Arrays.equals(tx.to, to)).collect(toList());
        List<Transaction> transactionsPrevBlocks = getTransactionsByFromAndTo(b.hashPrevBlock, from, to, offset, limit);
        transactionsPrevBlocks.addAll(transactions);
        return transactionsPrevBlocks;
    }

    private List<Transaction> getTransactionsByToAndType(int type, byte[] blockHash, byte[] publicKeyHash, int offset, int limit) {
        if (Arrays.equals(blockHash, latestConfirmed.getHash())) {
            return bc.getTransactionsByToAndType(type, publicKeyHash, offset, limit);
        }
        Block b = blocksCache.getBlock(blockHash);
        if (b == null) {
            return new ArrayList<>();
        }
        if (b.body == null) {
            return getTransactionsByToAndType(type, b.hashPrevBlock, publicKeyHash, offset, limit);
        }
        List<Transaction> transactions = b.body.stream().filter(tx -> tx.type == type && Arrays.equals(tx.to, publicKeyHash)).collect(toList());
        List<Transaction> transactionsPrevBlocks = getTransactionsByToAndType(type, b.hashPrevBlock, publicKeyHash, offset, limit);
        transactionsPrevBlocks.addAll(transactions);
        return transactionsPrevBlocks;
    }

    private List<Transaction> getTransactionsByFromAndType(int type, byte[] blockHash, byte[] publicKey, int offset, int limit) {
        if (Arrays.equals(blockHash, latestConfirmed.getHash())) {
            return bc.getTransactionsByFromAndType(type, publicKey, offset, limit);
        }
        Block b = blocksCache.getBlock(blockHash);
        if (b == null) {
            return new ArrayList<>();
        }
        if (b.body == null) {
            return getTransactionsByFromAndType(type, b.hashPrevBlock, publicKey, offset, limit);
        }
        List<Transaction> transactions = b.body.stream().filter(tx -> tx.type == type && Arrays.equals(tx.from, publicKey)).collect(toList());
        List<Transaction> transactionsPrevBlocks = getTransactionsByFromAndType(type, b.hashPrevBlock, publicKey, offset, limit);
        transactionsPrevBlocks.addAll(transactions);
        return transactionsPrevBlocks;
    }

    private List<Transaction> getTransactionsByFromToAndType(int type, byte[] blockHash, byte[] from, byte[] to, int offset, int limit) {
        if (Arrays.equals(blockHash, latestConfirmed.getHash())) {
            return bc.getTransactionsByFromToAndType(type, from, to, offset, limit);
        }
        Block b = blocksCache.getBlock(blockHash);
        if (b == null) {
            return new ArrayList<>();
        }
        if (b.body == null) {
            return getTransactionsByFromToAndType(type, b.hashPrevBlock, from, to, offset, limit);
        }
        List<Transaction> transactions = b.body.stream().filter(tx -> tx.type == type && Arrays.equals(tx.from, from) && Arrays.equals(tx.to, to)).collect(toList());
        List<Transaction> transactionsPrevBlocks = getTransactionsByFromToAndType(type, b.hashPrevBlock, from, to, offset, limit);
        transactionsPrevBlocks.addAll(transactions);
        return transactionsPrevBlocks;
    }

}

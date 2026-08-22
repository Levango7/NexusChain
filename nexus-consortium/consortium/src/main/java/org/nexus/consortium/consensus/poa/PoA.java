package org.nexus.consortium.consensus.poa;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.nexus.common.*;
import org.nexus.consortium.Start;
import org.nexus.consortium.consensus.poa.config.Genesis;
import org.nexus.consortium.state.Account;
import org.nexus.consortium.util.FileUtils;
import org.nexus.exception.ConsensusEngineLoadException;

import java.util.*;

import static org.nexus.consortium.consensus.poa.PoAHashPolicy.HASH_POLICY;

// poa is a minimal non-trivial consensus engine
@Slf4j
public class PoA implements ConsensusEngine, PeerServerListener {
    private PoAConfig poAConfig;

    private Miner miner;

    private BatchAbleStore store;

    @Override
    public Miner miner() {
        return miner;
    }

    @Override
    public StateRepository repository() {
        return repository;
    }

    public HashPolicy policy() {
        return HASH_POLICY;
    }

    private Validator validator;

    private StateRepository repository;

    // P1-10: 保存 load 时传入的 ConsortiumRepository，供 onMessage 查询父区块
    private ConsortiumRepository consortiumRepository;

    private Genesis genesis;

    private Block genesisBlock;

    public PoA() {
        this.validator = new PoaValidator();
    }

    /**
     * P1-10: 注入持久化存储，用于 onMessage 收到合法区块后落盘。
     */
    public void setStore(BatchAbleStore store) {
        this.store = store;
    }

    @Override
    public Block genesis() {
        if (genesisBlock != null) return genesisBlock;
        genesisBlock = genesis.getBlock();
        return genesisBlock;
    }


    @Override
    public void load(Properties properties, ConsortiumRepository repository) throws ConsensusEngineLoadException {
        JavaPropsMapper mapper = new JavaPropsMapper();
        ObjectMapper objectMapper = new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS);
        try{
            poAConfig = mapper.readPropertiesAs(properties, PoAConfig.class);
        }catch (Exception e){
            String schema = "";
            try{
                schema = mapper.writeValueAsProperties(new PoAConfig()).toString();
            }catch (Exception ignored){};
            throw new ConsensusEngineLoadException(
                    "load properties failed :" + properties.toString() + " expecting " + schema
            );
        }
        PoAMiner poaMiner = new PoAMiner();
        Resource resource;
        try{
            resource = FileUtils.getResource(poAConfig.getGenesis());
        }catch (Exception e){
            throw new ConsensusEngineLoadException(e.getMessage());
        }
        try{
            genesis = objectMapper.readValue(resource.getInputStream(), Genesis.class);
        }catch (Exception e){
            throw new ConsensusEngineLoadException("failed to parse genesis");
        }
        poaMiner.setPoAConfig(poAConfig);
        poaMiner.setGenesis(genesis);
        poaMiner.setRepository(repository);
        this.miner = poaMiner;

        this.repository = new ConsortiumStateRepository();
        // P1-10: 保存 ConsortiumRepository 引用，供 onMessage 查询父区块
        this.consortiumRepository = repository;

        // register miner accounts
        this.repository.register(genesis(), Collections.singleton(new Account(poaMiner.minerPublicKeyHash, 0)));
    }

    @Override
    public Validator validator() {
        return validator;
    }

    @Override
    public ConfirmedBlocksProvider provider() {
        return unconfirmed -> unconfirmed;
    }

    @Override
    public PeerServerListener handler() {
        return this;
    }

    @Override
    public void onMessage(Context context, PeerServer server) {
        // P1-10: 实现收到区块广播的验证与写入，替换原空实现。
        byte[] raw = context.getMessage();
        if (raw == null || raw.length == 0) {
            return;
        }
        Block block;
        try {
            block = Start.MAPPER.readValue(raw, Block.class);
        } catch (Exception e) {
            log.warn("onMessage: deserialize block failed from peer {}: {}",
                    context.getRemote(), e.getMessage());
            return;
        }
        if (block == null || block.getHeader() == null) {
            log.warn("onMessage: received null block or header from peer {}", context.getRemote());
            return;
        }
        // 基本合法性校验：高度连续性、hashPrev 匹配、proposer 合法性
        Optional<Block> parentOpt;
        try {
            parentOpt = consortiumRepository == null ? Optional.empty() :
                    consortiumRepository.getBlock(block.getHashPrev().getBytes());
        } catch (Exception e) {
            log.warn("onMessage: fetch parent block failed, hashPrev={}: {}",
                    block.getHashPrev(), e.getMessage());
            return;
        }
        if (!parentOpt.isPresent()) {
            log.warn("onMessage: parent block not found, hashPrev={}, height={}, discard block",
                    block.getHashPrev(), block.getHeight());
            return;
        }
        Block parent = parentOpt.get();
        if (parent.getHeight() + 1 != block.getHeight()) {
            log.warn("onMessage: block height not continuous, parent={}, received={}, discard",
                    parent.getHeight(), block.getHeight());
            return;
        }
        if (!parent.getHash().equals(block.getHashPrev())) {
            log.warn("onMessage: hashPrev mismatch, parent.hash={}, block.hashPrev={}, discard",
                    parent.getHash(), block.getHashPrev());
            return;
        }
        // proposer 合法性：proposer 必须在 genesis.miners 列表中
        String proposer = block.getHeader().getProposer();
        if (proposer == null || proposer.isEmpty() ||
                genesis == null || genesis.miners == null ||
                genesis.miners.stream().noneMatch(m -> proposer.equals(m.address))) {
            log.warn("onMessage: invalid proposer={}, height={}, discard", proposer, block.getHeight());
            return;
        }
        // 使用 PoaValidator 验证区块（含 hash 校验）
        ValidateResult vr;
        try {
            vr = validator.validate(block, parent);
        } catch (Exception e) {
            log.warn("onMessage: validate block failed, height={}: {}", block.getHeight(), e.getMessage());
            return;
        }
        if (!vr.isSuccess()) {
            log.warn("onMessage: block validation failed, height={}, reason={}, discard",
                    block.getHeight(), vr.getReason());
            return;
        }
        // 验证通过：写入 store 落盘
        if (store != null) {
            try {
                store.put(block.getHash().getBytes(), block);
                log.info("onMessage: block accepted and stored, height={}, hash={}",
                        block.getHeight(), block.getHash());
            } catch (Exception e) {
                log.error("onMessage: store block failed, height={}: {}",
                        block.getHeight(), e.getMessage(), e);
            }
        } else {
            log.warn("onMessage: store not injected, block height={} not persisted", block.getHeight());
        }
    }

    @Override
    public void onStart(PeerServer server) {
        miner.addListeners(new MinerListener() {
            @Override
            public void onBlockMined(Block block) {
                try {
                    server.broadcast(Start.MAPPER.writeValueAsBytes(block));
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onMiningFailed(Block block) {

            }
        });
    }

    @Override
    public void onNewPeer(Peer peer, PeerServer server) {

    }

    @Override
    public void onDisconnect(Peer peer, PeerServer server) {

    }
}

package org.nexus.consortium.consensus.poa;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper;
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
public class PoA implements ConsensusEngine, PeerServerListener {
    private PoAConfig poAConfig;

    private Miner miner;

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

    private Genesis genesis;

    private Block genesisBlock;

    public PoA() {
        this.validator = new PoaValidator();
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

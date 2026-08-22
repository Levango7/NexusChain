package org.nexus.consortium;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.Assert;
import org.nexus.common.*;
import org.nexus.consortium.consensus.None;
import org.nexus.consortium.consensus.poa.PoA;
import org.nexus.consortium.net.GRpcPeerServer;
import org.nexus.consortium.net.WebSocketPeerServer;
import org.nexus.consortium.storage.LevelDbStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@EnableAsync
@EnableScheduling
@SpringBootApplication
@EnableTransactionManagement
@Slf4j
// use SPRING_CONFIG_LOCATION environment to locate spring config
// for example: SPRING_CONFIG_LOCATION=classpath:\application.yml,some-path\custom-config.yml
public class Start {
    private static final boolean ENABLE_ASSERTION = "true".equals(System.getenv("ENABLE_ASSERTION"));

    public static final Executor APPLICATION_THREAD_POOL = Executors.newCachedThreadPool();

    public static void devAssert(boolean truth, String error){
        if (!ENABLE_ASSERTION) return;
        Assert.isTrue(truth, error);
    }

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(JsonParser.Feature.ALLOW_COMMENTS);

    public static void main(String[] args) {
        SpringApplication.run(Start.class, args);
    }

    @Bean
    public ObjectMapper getObjectMapper() {
        return MAPPER;
    }

    @Bean
    public Miner miner(ConsensusEngine engine, NewMinedBlockWriter writer){
        Miner miner = engine.miner();
        miner.addListeners(writer);
        miner.start();
        return miner;
    }

    @Bean
    public StateRepository stateRepository(ConsensusEngine engine){return engine.repository();}

    @Bean
    public PendingTransactionValidator transactionValidator(ConsensusEngine engine){return engine.validator();}

    @Bean
    public ConsensusEngine consensusEngine(ConsensusProperties consensusProperties, ConsortiumRepository consortiumRepository, BatchAbleStore store) throws Exception {
        String name = consensusProperties.getProperty(ConsensusProperties.CONSENSUS_NAME);
        name = name == null ? "" : name;
        final ConsensusEngine engine;
        switch (name.trim().toLowerCase()) {
            // none consensus selected, used for unit test
            case ApplicationConstants.CONSENSUS_NONE:
                log.warn("none consensus engine selected, please ensure you are in test mode");
                return new None();
            case ApplicationConstants.CONSENSUS_POA:
                // use poa as default consensus
                // another engine: pow, pos, pow+pos, vrf
                engine = new PoA();
                break;
            default:
                log.error(
                        "none available consensus configured by consortium.consensus.name=" + name +
                                " please provide available consensus engine");
                log.error("roll back to poa consensus");
                engine = new PoA();
        }
        // P1-10: 向 PoA 注入持久化存储，供 onMessage 收到合法区块后落盘
        if (engine instanceof PoA) {
            ((PoA) engine).setStore(store);
        }
        engine.load(consensusProperties, consortiumRepository);
        consortiumRepository.setProvider(engine.provider());
        // register event listeners
        consortiumRepository.addListeners(engine.repository());
        consortiumRepository.saveGenesis(engine.genesis());
        return engine;
    }

    abstract static class NewMinedBlockWriter implements MinerListener{
    }

    // create a miner listener for write block
    @Bean
    public NewMinedBlockWriter newMinedBlockWriter(ConsortiumRepository repository, ConsensusEngine engine){
        return new NewMinedBlockWriter() {
            @Override
            public void onBlockMined(Block block) {
                Optional<Block> o = repository.getBlock(block.getHashPrev().getBytes());
                if (!o.isPresent()) return;
                if (engine.validator().validate(block, o.get()).isSuccess()){
                    repository.writeBlock(block);
                }
            }

            @Override
            public void onMiningFailed(Block block) {

            }
        };
    }

    // create peer server from properties
    @Bean
    public PeerServer peerServer(PeerServerProperties properties, ConsensusEngine engine) throws Exception{
        PeerServer peerServer;
        String name = Optional.ofNullable(properties.getProperty("name")).orElse("");
        switch (name.trim().toLowerCase()){
            case "websocket":
                peerServer = new WebSocketPeerServer();
                break;
            default:
                peerServer = new GRpcPeerServer();
        }
        peerServer.load(properties);
        peerServer.use(engine.handler());
        peerServer.start();
        return peerServer;
    }

    // create leveldb/rocksdb here
    // P1-9: 替换 no-op 实现，使用 LevelDB 持久化存储使区块数据真正落盘。
    // 数据目录通过配置项 consortium.data-dir 指定，默认 ./data/leveldb。
    @Bean
    public BatchAbleStore store(GlobalConfig globalConfig){
        Object dataDirObj = globalConfig.get("data-dir");
        String dataDir = dataDirObj == null ? "./data/leveldb" : String.valueOf(dataDirObj);
        log.info("init LevelDbStore at {}", dataDir);
        return new LevelDbStore(dataDir);
    }
}

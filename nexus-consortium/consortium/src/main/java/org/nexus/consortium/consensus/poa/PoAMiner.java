package org.nexus.consortium.consensus.poa;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.springframework.util.Assert;
import org.nexus.common.*;
import org.nexus.consortium.account.PublicKeyHash;
import org.nexus.consortium.consensus.poa.config.Genesis;
import org.nexus.exception.ConsensusEngineLoadException;
import org.nexus.util.BigEndian;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.nexus.consortium.consensus.poa.PoAHashPolicy.HASH_POLICY;


@Slf4j
public class PoAMiner implements Miner {
    private PoAConfig poAConfig;

    PublicKeyHash minerPublicKeyHash;

    private Genesis genesis;

    private List<MinerListener> listeners;

    private BlockRepository blockRepository;

    private boolean stopped;

    private Thread thread;

    public void setGenesis(Genesis genesis) {
        this.genesis = genesis;
    }

    public void setPoAConfig(PoAConfig poAConfig) throws ConsensusEngineLoadException {
        this.poAConfig = poAConfig;
        this.minerPublicKeyHash = PublicKeyHash.from(poAConfig.getMinerCoinBase()).orElseThrow(
                () -> new ConsensusEngineLoadException("invalid address " + poAConfig.getMinerCoinBase())
        );
    }

    public void setRepository(BlockRepository blockRepository) {
        this.blockRepository = blockRepository;
    }

    public PoAMiner() {
        listeners = new ArrayList<>();
    }

    public PoAMiner(PoAConfig poAConfig, Genesis genesis) {
        this.poAConfig = poAConfig;
        this.genesis = genesis;
    }

    public Optional<Proposer> getProposer(Block parent, long currentTimeSeconds) {
        if (genesis.miners.size() == 0) {
            return Optional.empty();
        }
        if (parent.getHeight() == 0) {
            return Optional.of(new Proposer(genesis.miners.get(0).address, 0, Long.MAX_VALUE));
        }
        if (parent.getBody() == null ||
                parent.getBody().size() == 0 ||
                parent.getBody().get(0).getTo() == null
        ) return Optional.empty();
        String prev = new PublicKeyHash(parent.getBody().get(0).getTo().getBytes()).getAddress();
        int prevIndex = genesis.miners.stream().map(x -> x.address).collect(Collectors.toList()).indexOf(prev);
        if (prevIndex < 0) {
            return Optional.empty();
        }

        long step = (currentTimeSeconds - parent.getCreatedAt())
                / poAConfig.getBlockInterval() + 1;

        int currentIndex = (int) (prevIndex + step) % genesis.miners.size();
        long endTime = parent.getCreatedAt() + step * poAConfig.getBlockInterval();
        long startTime = endTime - poAConfig.getBlockInterval();
        return Optional.of(new Proposer(
                genesis.miners.get(currentIndex).address,
                startTime,
                endTime
        ));
    }


    @Override
    public void start() {
        thread = new Thread(() -> {
            while (true){
                tryMine();
                try {
                    TimeUnit.SECONDS.sleep(poAConfig.getBlockInterval());
                }catch (Exception ignored){}
            }
        });
        thread.start();
    }

    @Override
    public void stop() {
        if (thread != null){
            thread.interrupt();
        }
        stopped = true;
    }

    public void tryMine() {
        if (!poAConfig.isEnableMining() || stopped) {
            return;
        }
        String coinBase = poAConfig.getMinerCoinBase();
        Block best = blockRepository.getBestBlock();
        // 判断是否轮到自己出块
        Optional<Proposer> o = getProposer(
                best,
                OffsetDateTime.now().toEpochSecond()
        ).filter(p -> p.getAddress().equals(coinBase));
        if (!o.isPresent()) return;
        log.info("try to mining at height " + (best.getHeight() + 1));
        try {
            Block b = createBlock(blockRepository.getBestBlock());
            log.info("mining success");
            listeners.forEach(l -> l.onBlockMined(b));
            Assert.isTrue(b.getHash().equals(new HexBytes(PoAUtils.getHash(b))), "block hash is equal");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Transaction createCoinBase(long height) throws DecoderException {
        Transaction tx = Transaction.builder()
                .height(height)
                .version(PoAConstants.TRANSACTION_VERSION)
                .createdAt(System.currentTimeMillis() / 1000)
                .nonce(height)
                .from(PoAConstants.ZERO_BYTES)
                .amount(EconomicModelImpl.getConsensusRewardAtHeight(height))
                .payload(PoAConstants.ZERO_BYTES)
                .to(new HexBytes(minerPublicKeyHash.getPublicKeyHash()))
                .signature(PoAConstants.ZERO_BYTES).build();
        tx.setHash(HASH_POLICY.getHash(tx));
        return tx;
    }

    private Block createBlock(Block parent) throws DecoderException {
        Header header = Header.builder()
                .version(parent.getVersion())
                .hashPrev(parent.getHash())
                .merkleRoot(PoAConstants.ZERO_BYTES)
                .height(parent.getHeight() + 1)
                .createdAt(System.currentTimeMillis() / 1000)
                .payload(PoAConstants.ZERO_BYTES)
                .hash(new HexBytes(BigEndian.encodeInt64(parent.getHeight() + 1))).build();
        Block b = new Block(header);
        b.getBody().add(createCoinBase(parent.getHeight() + 1));
        b.setHash(HASH_POLICY.getHash(b));
        return b;
    }


    @Override
    public void addListeners(MinerListener... listeners) {
        this.listeners.addAll(Arrays.asList(listeners));
    }

    @Override
    public void onBlockWritten(Block block) {
    }

    @Override
    public void onNewBestBlock(Block block) {
    }

    @Override
    public void onBlockConfirmed(Block block) {

    }


    public static class EconomicModelImpl {

        private static final long INITIAL_SUPPLY = 20;

        private static final long HALF_PERIOD = 10000000;


        public static long getConsensusRewardAtHeight(long height) {
            long era = height / HALF_PERIOD;
            long reward = INITIAL_SUPPLY;
            for (long i = 0; i < era; i++) {
                reward = reward * 52218182 / 100000000;
            }
            return reward;
        }

    }
}

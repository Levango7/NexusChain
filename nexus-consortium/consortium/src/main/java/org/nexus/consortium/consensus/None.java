package org.nexus.consortium.consensus;

import org.nexus.common.*;
import org.nexus.exception.ConsensusEngineLoadException;
import org.nexus.exception.StateUpdateException;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

// the "none" consensus do nothing, used for unit tests
public class None implements ConsensusEngine {
    @Override
    public void load(Properties properties, ConsortiumRepository repository) throws ConsensusEngineLoadException {

    }

    @Override
    public Block genesis() {
        return new Block();
    }

    @Override
    public Validator validator() {
        return new Validator() {
            @Override
            public ValidateResult validate(Block block, Block dependency) {
                return null;
            }

            @Override
            public ValidateResult validate(Transaction transaction) {
                return null;
            }
        };
    }

    @Override
    public Miner miner() {
        return new Miner() {
            @Override
            public void start() {

            }

            @Override
            public void stop() {

            }

            @Override
            public void addListeners(MinerListener... listeners) {

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
        };
    }

    @Override
    public ConfirmedBlocksProvider provider() {
        return new ConfirmedBlocksProvider() {
            @Override
            public List<Block> getConfirmed(List<Block> unconfirmed) {
                return null;
            }
        };
    }

    @Override
    public HashPolicy policy() {
        return new HashPolicy() {
            @Override
            public HexBytes getHash(Block block) {
                return null;
            }

            @Override
            public HexBytes getHash(Transaction transaction) {
                return null;
            }

            @Override
            public HexBytes getHash(Header header) {
                return null;
            }
        };
    }

    @Override
    public StateRepository repository() {
        return new StateRepository() {
            @Override
            public <T extends State<T>> void register(Block genesis, T genesisState) throws StateUpdateException {

            }

            @Override
            public <T extends ForkAbleState<T>> void register(Block genesis, Collection<? extends T> forkAbleStates) {

            }

            @Override
            public <T extends State<T>> Optional<T> get(byte[] hash, Class<T> clazz) {
                return Optional.empty();
            }

            @Override
            public <T extends ForkAbleState<T>> Optional<T> get(String id, byte[] hash, Class<T> clazz) {
                return Optional.empty();
            }

            @Override
            public <T extends State<T>> T getLastConfirmed(Class<T> clazz) {
                return null;
            }

            @Override
            public <T extends ForkAbleState<T>> T getLastConfirmed(String id, Class<T> clazz) {
                return null;
            }

            @Override
            public void update(Block b) {

            }

            @Override
            public void put(Chained chained, State state) {

            }

            @Override
            public void put(Chained chained, Collection<ForkAbleState> forkAbleStates, Class<? extends ForkAbleState> clazz) {

            }

            @Override
            public void confirm(byte[] hash) {

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
        };
    }

    @Override
    public PeerServerListener handler() {
        return new PeerServerListener() {
            @Override
            public void onMessage(Context context, PeerServer server) {

            }

            @Override
            public void onStart(PeerServer server) {

            }

            @Override
            public void onNewPeer(Peer peer, PeerServer server) {

            }

            @Override
            public void onDisconnect(Peer peer, PeerServer server) {

            }
        };
    }
}

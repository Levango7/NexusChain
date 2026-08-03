package org.nexus.consortium.pool;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.nexus.common.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class TransactionPool implements org.nexus.common.TransactionPool {
    private static class TransactionWeigher implements Weigher<String, Transaction>{
        @Override
        public int weigh(String key, Transaction value) {
            return value.size();
        }
    }

    private Cache<String, Transaction> cache;

    private PendingTransactionValidator validator;

    private List<TransactionPoolListener> listeners = new ArrayList<>();

    public TransactionPool(){
        cache = CacheBuilder.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .weigher(new TransactionWeigher())
                .maximumWeight(256 * Constants.MEGA_BYTES).build();
    }

    @Override
    public void collect(Transaction... transactions) {
        for(Transaction transaction: transactions){
            String k = transaction.getHash().toString();
            if (cache.asMap().containsKey(k)) continue;
            if(validator.validate(transaction).isSuccess()){
                cache.put(transaction.getHash().toString(), transaction);
                listeners.forEach(c -> c.onNewTransactionCollected(transaction));
            }
        }
    }

    @Override
    public Optional<Transaction> pop() {
        if (cache.asMap().isEmpty()){
            return Optional.empty();
        }
        Optional<Transaction>
        o = cache.asMap().values().stream().sorted((a, b) -> (int) (a.getNonce() - b.getNonce())).findFirst();
        if (!o.isPresent()) return o;
        cache.asMap().remove(o.get().getHash().toString());
        return o;
    }

    @Override
    public List<Transaction> pop(int limit) {
        List<Transaction> list = new ArrayList<>();
        for(int i = 0; i < limit; i++){
            Optional<Transaction> o = pop();
            if (!o.isPresent()) return list;
            list.add(o.get());
        }
        return list;
    }

    @Override
    public int size() {
        return (int) cache.size();
    }

    @Override
    public List<Transaction> get(int page, int size) {
        return cache.asMap().values().stream()
                .skip(page * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    @Autowired
    @Override
    public void setValidator(PendingTransactionValidator validator) {
        this.validator = validator;
    }

    @Override
    public void addListeners(TransactionPoolListener... listeners) {
        this.listeners.addAll(Arrays.asList(listeners));
    }

    @Override
    public void onBlockWritten(Block block) {

    }

    @Override
    public void onNewBestBlock(Block block) {
        block.getBody().forEach(tx -> cache.asMap().remove(tx.getHash().toString()));
    }

    @Override
    public void onBlockConfirmed(Block block) {

    }


    @Override
    public void onBlockMined(Block block) {

    }

    @Override
    public void onMiningFailed(Block block) {
        block.getBody().forEach(this::collect);
    }
}

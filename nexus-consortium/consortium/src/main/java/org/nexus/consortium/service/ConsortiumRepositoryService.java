package org.nexus.consortium.service;

import lombok.experimental.Delegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.nexus.common.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class ConsortiumRepositoryService implements ConsortiumRepository {
    private abstract static class ExcludedMethods{
        public abstract void writeBlock(Block block);
    }
    @Qualifier("blockRepositoryService")
    @Autowired
    @Delegate(excludes = ExcludedMethods.class)
    private BlockRepository blockRepository;

    @Qualifier("transactionRepositoryService")
    @Autowired
    @Delegate
    private TransactionRepository transactionRepository;

    private List<ConsortiumRepositoryListener> listeners = new ArrayList<>();

    @Override
    public Block getLastConfirmed() {
        return null;
    }

    @Override
    public List<Block> getUnconfirmed() {
        return null;
    }

    @Override
    public void setProvider(ConfirmedBlocksProvider provider) {

    }

    @Override
    public void addListeners(ConsortiumRepositoryListener... listeners) {
        this.listeners.addAll(Arrays.asList(listeners));
    }

    @Override
    public boolean writeBlock(Block block) {
        blockRepository.writeBlock(block);
        listeners.forEach(l -> {
            l.onBlockWritten(block);
            l.onNewBestBlock(block);
            l.onBlockConfirmed(block);
        });
        return true;
    }
}

package org.nexus.common;

import java.util.List;

public interface ConsortiumRepository extends BlockRepository, TransactionRepository {
    Block getLastConfirmed();

    List<Block> getUnconfirmed();

    void setProvider(ConfirmedBlocksProvider provider);

    void addListeners(ConsortiumRepositoryListener... listeners);
}

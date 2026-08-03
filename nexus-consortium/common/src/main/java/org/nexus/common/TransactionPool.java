package org.nexus.common;

import java.util.List;
import java.util.Optional;

public interface TransactionPool extends MinerListener, ConsortiumRepositoryListener{

    // collect transactions into transaction pool
    void collect(Transaction... transactions);

    // pop a transaction from pool
    Optional<Transaction> pop();

    // pop at most n transactions
    // if limit < 0, pop all transactions
    List<Transaction> pop(int limit);

    // get size of current transaction pool
    int size();

    // get transactions paged
    List<Transaction> get(int page, int size);

    void setValidator(PendingTransactionValidator validator);

    void addListeners(TransactionPoolListener... listeners);
}

package org.nexus.common;

public interface TransactionPoolListener {
    void onNewTransactionCollected(Transaction transaction);
}

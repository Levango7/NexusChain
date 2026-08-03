package org.nexus.merkletree;

import org.nexus.core.account.Transaction;

public class MerkleTransaction {

    private Transaction transaction;

    private int index;

    public MerkleTransaction(int index, Transaction transaction) {
        this.transaction = transaction;
        this.index = index;
    }

    public MerkleTransaction() {
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }
}

package org.nexus.common;

import lombok.extern.slf4j.Slf4j;
import org.nexus.exception.StateUpdateException;

import java.util.*;

public interface TransactionRepository {
    boolean hasTransaction(byte[] hash);

    boolean hasPayload(byte[] payload);

    Optional<Transaction> getTransactionByHash(byte[] hash);

    List<Transaction> getTransactionsByFrom(byte[] from, int page, int size);

    List<Transaction> getTransactionsByFromAndType(byte[] from, int type, int page, int size);

    List<Transaction> getTransactionsByTo(byte[] to, int page, int size);

    List<Transaction> getTransactionsByToAndType(byte[] to, int type, int page, int size);

    List<Transaction> getTransactionsByFromAndTo(byte[] from, byte[] to, int page, int size);

    List<Transaction> getTransactionsByFromAndToAndType(byte[] from, byte[] to, int type, int page, int size);

    List<Transaction> getTransactionsByPayload(byte[] payload, int page, int size);

    List<Transaction> getTransactionsByPayloadAndType(byte[] payload, int type, int page, int size);

    List<Transaction> getTransactionsByBlockHash(byte[] blockHash, int page, int size);

    List<Transaction> getTransactionsByBlockHeight(long height, int page, int size);

}

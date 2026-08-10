package org.nexus.consortium.service;

import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.nexus.common.Transaction;
import org.nexus.common.TransactionRepository;
import org.nexus.consortium.dao.Mapping;
import org.nexus.consortium.dao.TransactionDao;

import java.util.List;
import java.util.Optional;

@Service
public class TransactionRepositoryService implements TransactionRepository {
    @Autowired
    private TransactionDao transactionDao;

    @Override
    public boolean hasTransaction(@NonNull byte[] hash) {
        return transactionDao.existsById(hash);
    }

    @Override
    public boolean hasPayload(@NonNull byte[] payload) {
        return transactionDao.existsByPayload(payload);
    }

    @Override
    public Optional<Transaction> getTransactionByHash(byte[] hash) {
        return transactionDao.findById(hash).map(Mapping::getFromTransactionEntity);
    }

    @Override
    public List<Transaction> getTransactionsByFrom(byte[] from, int page, int size) {
        return Mapping.getFromTransactionEntities(
                transactionDao.findByFromOrderByHeightAscPositionAsc(from, PageRequest.of(page, size))
        );
    }

    @Override
    public List<Transaction> getTransactionsByFromAndType(byte[] from, int type, int page, int size) {
        return Mapping.getFromTransactionEntities(
                transactionDao.findByFromAndTypeOrderByHeightAscPositionAsc(from, type, PageRequest.of(page, size))
        );
    }

    @Override
    public List<Transaction> getTransactionsByTo(byte[] to, int page, int size) {
        return Mapping.getFromTransactionEntities(transactionDao.findByToOrderByHeightAscPositionAsc(to, PageRequest.of(page, size)));
    }

    @Override
    public List<Transaction> getTransactionsByToAndType(byte[] to, int type, int page, int size) {
        return Mapping.getFromTransactionEntities(transactionDao.findByToAndTypeOrderByHeightAscPositionAsc(to, type, PageRequest.of(page, size)));
    }

    @Override
    public List<Transaction> getTransactionsByFromAndTo(byte[] from, byte[] to, int page, int size) {
        return Mapping.getFromTransactionEntities(transactionDao.findByFromAndToOrderByHeightAscPositionAsc(from, to, PageRequest.of(page, size)));
    }

    @Override
    public List<Transaction> getTransactionsByFromAndToAndType(byte[] from, byte[] to, int type, int page, int size) {
        return Mapping.getFromTransactionEntities(
                transactionDao.findByFromAndToAndTypeOrderByHeightAscPositionAsc(
                        from, to, type, PageRequest.of(page, size)
                )
        );
    }

    @Override
    public List<Transaction> getTransactionsByPayload(byte[] payload, int page, int size) {
        return Mapping.getFromTransactionEntities(
                transactionDao.findByPayloadOrderByHeightAscPositionAsc(payload, PageRequest.of(page, size))
        );
    }

    @Override
    public List<Transaction> getTransactionsByPayloadAndType(byte[] payload, int type, int page, int size) {
        return Mapping.getFromTransactionEntities(
                transactionDao.findByPayloadAndTypeOrderByHeightAscPositionAsc(payload, type, PageRequest.of(page, size))
        );
    }


    @Override
    public List<Transaction> getTransactionsByBlockHash(byte[] blockHash, int page, int size) {
        return Mapping.getFromTransactionEntities(
                transactionDao.findByBlockHashOrderByPosition(blockHash, PageRequest.of(page, size))
        );
    }

    @Override
    public List<Transaction> getTransactionsByBlockHeight(long height, int page, int size) {
        return Mapping.getFromTransactionEntities(
                transactionDao.findByHeightOrderByPosition(height, PageRequest.of(page, size))
        );
    }
}

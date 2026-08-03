package org.nexus.bridge;

import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.model.BridgeTransaction.BridgeOperationType;
import org.nexus.bridge.model.BridgeTransaction.BridgeTxStatus;
import org.nexus.bridge.repository.BridgeTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BridgeServiceImpl implements BridgeService {

    private final BridgeConfig config;
    private final BridgeTransactionRepository txRepository;
    private final AtomicReference<BridgeState> bridgeState = new AtomicReference<>(BridgeState.ACTIVE);
    private final AtomicLong dailyUsed = new AtomicLong(0);
    private volatile long dailyResetTime = System.currentTimeMillis();

    public BridgeServiceImpl(BridgeConfig config, BridgeTransactionRepository txRepository) {
        this.config = config;
        this.txRepository = txRepository;
    }

    @Override
    @Transactional
    public BridgeTransaction lock(LockRequest request) {
        requireState(BridgeState.ACTIVE, "LOCK requires ACTIVE bridge");
        validateAmount(request.getAmount());

        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId(UUID.randomUUID().toString());
        tx.setOperationType(BridgeOperationType.BRIDGE_LOCK);
        tx.setSourceChainId(request.getSourceChainId());
        tx.setTargetChainId(request.getTargetChainId());
        tx.setAmount(request.getAmount());
        tx.setUserAddress(request.getUserAddress());
        tx.setTargetAddress(request.getTargetAddress());
        tx.setSourceTxHash(request.getSourceTxHash());
        tx.setValidatorIds(new HashSet<>());
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());
        tx.setMemo(request.getMemo());

        if (config.isLargeAmount(request.getAmount())) {
            tx.setStatus(BridgeTxStatus.LOCK_PENDING);
            tx.setTimelockExpiresAt(Instant.now().plusSeconds(config.getTimelockPeriodSeconds()));
        } else {
            tx.setStatus(BridgeTxStatus.LOCKED);
        }

        return txRepository.save(tx);
    }

    @Override
    @Transactional
    public BridgeTransaction mint(MintRequest request) {
        requireState(BridgeState.ACTIVE, "MINT requires ACTIVE bridge");

        BridgeTransaction lockTx = txRepository.findById(request.getLockTxId())
                .orElseThrow(() -> new BridgeException("Lock transaction not found: " + request.getLockTxId()));
        if (lockTx.getStatus() != BridgeTxStatus.LOCKED)
            throw new BridgeException("Lock tx not in LOCKED state, current: " + lockTx.getStatus());

        if (lockTx.getTimelockExpiresAt() != null && Instant.now().isBefore(lockTx.getTimelockExpiresAt()))
            throw new BridgeException("Timelock not yet expired, wait until: " + lockTx.getTimelockExpiresAt());

        Set<String> signers = request.getSignatures() != null ? request.getSignatures().keySet() : Collections.emptySet();
        if (!BridgeValidator.meetsThreshold(signers, config))
            throw new BridgeException("Insufficient signatures: " + signers.size() + "/" + config.getSignatureThreshold());

        lockTx.setStatus(BridgeTxStatus.MINTED);
        lockTx.setValidatorIds(new HashSet<>(signers));
        lockTx.setUpdatedAt(Instant.now());
        return txRepository.save(lockTx);
    }

    @Override
    @Transactional
    public BridgeTransaction burn(BurnRequest request) {
        if (bridgeState.get() == BridgeState.EMERGENCY_STOP)
            throw new BridgeException("Bridge is EMERGENCY_STOP, all operations forbidden");
        validateAmount(request.getAmount());

        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId(UUID.randomUUID().toString());
        tx.setOperationType(BridgeOperationType.BRIDGE_BURN);
        tx.setSourceChainId(request.getSourceChainId());
        tx.setTargetChainId(request.getTargetChainId());
        tx.setAmount(request.getAmount());
        tx.setUserAddress(request.getUserAddress());
        tx.setTargetAddress(request.getTargetAddress());
        tx.setSourceTxHash(request.getSourceTxHash());
        tx.setValidatorIds(new HashSet<>());
        tx.setStatus(BridgeTxStatus.BURNED);
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());
        return txRepository.save(tx);
    }

    @Override
    @Transactional
    public BridgeTransaction unlock(UnlockRequest request) {
        if (bridgeState.get() == BridgeState.EMERGENCY_STOP)
            throw new BridgeException("Bridge is EMERGENCY_STOP, all operations forbidden");

        BridgeTransaction burnTx = txRepository.findById(request.getBurnTxId())
                .orElseThrow(() -> new BridgeException("Burn transaction not found: " + request.getBurnTxId()));
        if (burnTx.getStatus() != BridgeTxStatus.BURNED)
            throw new BridgeException("Burn tx not in BURNED state, current: " + burnTx.getStatus());

        Set<String> signers = request.getSignatures() != null ? request.getSignatures().keySet() : Collections.emptySet();
        if (!BridgeValidator.meetsThreshold(signers, config))
            throw new BridgeException("Insufficient signatures: " + signers.size() + "/" + config.getSignatureThreshold());

        burnTx.setStatus(BridgeTxStatus.UNLOCKED);
        burnTx.setValidatorIds(new HashSet<>(signers));
        burnTx.setUpdatedAt(Instant.now());
        return txRepository.save(burnTx);
    }

    @Override
    public BridgeTransaction getTransaction(String txId) {
        return txRepository.findById(txId).orElse(null);
    }

    @Override
    public BridgeTransaction getTransactionBySourceHash(String sourceTxHash) {
        return txRepository.findBySourceTxHash(sourceTxHash).orElse(null);
    }

    @Override
    public BridgeStatus getStatus() {
        resetDailyIfNeeded();
        BridgeStatus status = new BridgeStatus();
        status.setState(bridgeState.get());
        status.setDailyLimit(config.getDailyLimit());
        status.setDailyUsed(dailyUsed.get());
        status.setSignatureThreshold(config.getSignatureThreshold());
        status.setActiveValidatorCount(config.getValidatorPublicKeys() != null ? config.getValidatorPublicKeys().size() : 0);
        status.setPendingTxCount((int) txRepository.countByStatusIn(Arrays.asList(
                BridgeTxStatus.LOCK_PENDING, BridgeTxStatus.LOCKED, BridgeTxStatus.MINT_PENDING,
                BridgeTxStatus.BURN_PENDING, BridgeTxStatus.BURNED, BridgeTxStatus.UNLOCK_PENDING)));
        return status;
    }

    @Override
    public void pause(String validatorId) {
        if (bridgeState.get() == BridgeState.EMERGENCY_STOP)
            throw new BridgeException("Cannot pause: bridge is EMERGENCY_STOP");
        bridgeState.set(BridgeState.PAUSED);
    }

    @Override
    public void resume(Set<String> validatorIds) {
        if (bridgeState.get() != BridgeState.PAUSED)
            throw new BridgeException("Cannot resume: bridge is not PAUSED");
        if (!BridgeValidator.meetsThreshold(validatorIds, config))
            throw new BridgeException("Insufficient signatures to resume");
        bridgeState.set(BridgeState.ACTIVE);
    }

    private void requireState(BridgeState required, String msg) {
        if (bridgeState.get() != required) throw new BridgeException(msg + " (current: " + bridgeState.get() + ")");
    }

    private void validateAmount(long amount) {
        if (amount <= 0) throw new BridgeException("Amount must be positive");
        if (config.exceedsMaxAmount(amount)) throw new BridgeException("Amount exceeds single tx limit");
        resetDailyIfNeeded();
        // CAS loop to atomically check + reserve daily quota (prevents TOCTOU race)
        while (true) {
            long current = dailyUsed.get();
            if (config.exceedsDailyLimit(amount, current)) throw new BridgeException("Daily limit exceeded");
            if (dailyUsed.compareAndSet(current, current + amount)) break;
        }
    }

    private void resetDailyIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - dailyResetTime > 86_400_000L) { dailyUsed.set(0); dailyResetTime = now; }
    }
}
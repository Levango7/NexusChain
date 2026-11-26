package org.nexus.core;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.nexus.core.account.Account;
import org.nexus.encoding.BigEndian;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.merkletree.MerkleTree;
import org.nexus.util.ByteUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Versioned, deterministic account and payment-state commitment (ADR-037/038). */
@Component
public class StateCommitment {

    public static final byte VERSION = 0x02;
    public static final byte PAYMENT_VERSION = 0x03;
    public static final long DISABLED_ACTIVATION_HEIGHT = Long.MAX_VALUE;

    private final long activationHeight;
    private final long paymentActivationHeight;

    @Autowired
    public StateCommitment(
            @Value("${nexus.core.state-commitment-v2.activation-height:9223372036854775807}")
            long activationHeight,
            @Value("${nexus.payment.extension.activation-height:9223372036854775807}")
            long paymentActivationHeight) {
        if (activationHeight < 0 || paymentActivationHeight < 0) {
            throw new IllegalArgumentException("activation heights must be non-negative");
        }
        this.activationHeight = activationHeight;
        this.paymentActivationHeight = paymentActivationHeight;
    }

    /** Compatibility constructor: V2 may activate independently, V3 stays disabled. */
    public StateCommitment(long activationHeight) {
        this(activationHeight, DISABLED_ACTIVATION_HEIGHT);
    }

    /** Test/compatibility constructor for the legacy-only behavior. */
    public static StateCommitment disabled() {
        return new StateCommitment(DISABLED_ACTIVATION_HEIGHT, DISABLED_ACTIVATION_HEIGHT);
    }

    public boolean isV2Active(long blockHeight) {
        return activationHeight != DISABLED_ACTIVATION_HEIGHT && blockHeight >= activationHeight;
    }

    public boolean isV3Active(long blockHeight) {
        return paymentActivationHeight != DISABLED_ACTIVATION_HEIGHT
                && blockHeight >= paymentActivationHeight;
    }

    public byte[] calculateMerkleState(List<Account> accounts, long blockHeight) {
        return calculateMerkleState(accounts, List.of(), blockHeight);
    }

    public byte[] calculateMerkleState(
            List<Account> accounts, List<PaymentStateEntry> paymentStates, long blockHeight) {
        if (isV3Active(blockHeight)) {
            return calculateV3(accounts, paymentStates);
        }
        return isV2Active(blockHeight)
                ? calculateV2(accounts)
                : Block.calculateMerkleState(accounts);
    }

    /** Build the canonical V2 root described by ADR-037. */
    public static byte[] calculateV2(List<Account> accounts) {
        if (accounts == null) {
            throw new IllegalArgumentException("accounts must not be null");
        }
        if (accounts.isEmpty()) {
            return SHA3Utility.keccak256(new byte[]{VERSION});
        }

        List<Account> sorted = new ArrayList<>(accounts);
        sorted.sort(StateCommitment::compareAccounts);
        List<String> leaves = new ArrayList<>(sorted.size());
        Account previous = null;
        for (Account account : sorted) {
            if (previous != null && java.util.Arrays.equals(
                    previous.getPubkeyHash(), account.getPubkeyHash())) {
                throw new IllegalArgumentException("duplicate account in state commitment");
            }
            leaves.add(Hex.encodeHexString(encodeAccountV2(account)));
            previous = account;
        }
        try {
            return Hex.decodeHex(new MerkleTree(leaves).getRoot().getHash().toCharArray());
        } catch (RuntimeException | DecoderException e) {
            throw new IllegalArgumentException("could not calculate canonical state commitment", e);
        }
    }

    /** Build the canonical payment substate root described by ADR-038. */
    public static byte[] calculatePaymentRoot(List<PaymentStateEntry> paymentStates) {
        if (paymentStates == null) {
            throw new IllegalArgumentException("payment states must not be null");
        }
        if (paymentStates.isEmpty()) {
            return SHA3Utility.keccak256(new byte[]{PAYMENT_VERSION});
        }

        List<PaymentStateEntry> sorted = new ArrayList<>(paymentStates);
        sorted.sort(StateCommitment::comparePaymentStates);
        List<String> leaves = new ArrayList<>(sorted.size());
        PaymentStateEntry previous = null;
        for (PaymentStateEntry entry : sorted) {
            if (previous != null && previous.getKind() == entry.getKind()
                    && previous.getId().equals(entry.getId())) {
                throw new IllegalArgumentException("duplicate payment state entry");
            }
            leaves.add(Hex.encodeHexString(entry.encode()));
            previous = entry;
        }
        try {
            return Hex.decodeHex(new MerkleTree(leaves).getRoot().getHash().toCharArray());
        } catch (RuntimeException | DecoderException e) {
            throw new IllegalArgumentException("could not calculate payment state commitment", e);
        }
    }

    /** Domain-separated V3 commitment over both accounts and payment substate. */
    public static byte[] calculateV3(
            List<Account> accounts, List<PaymentStateEntry> paymentStates) {
        return SHA3Utility.keccak256(ByteUtil.merge(
                new byte[]{PAYMENT_VERSION},
                calculateV2(accounts),
                calculatePaymentRoot(paymentStates)));
    }

    static byte[] encodeAccountV2(Account account) {
        if (account == null || account.getPubkeyHash() == null) {
            throw new IllegalArgumentException("account and pubkeyHash must not be null");
        }
        byte[] pubkeyHash = account.getPubkeyHash();
        long blockHeight = account.getBlockHeight();
        if (pubkeyHash.length > BigEndian.MAX_UINT_16) {
            throw new IllegalArgumentException("pubkeyHash exceeds uint16 length");
        }
        if (blockHeight < 0 || blockHeight > BigEndian.MAX_UINT_32) {
            throw new IllegalArgumentException("account blockHeight exceeds uint32");
        }
        return ByteUtil.merge(
                new byte[]{VERSION},
                BigEndian.encodeUint16(pubkeyHash.length),
                pubkeyHash,
                BigEndian.encodeUint32(blockHeight),
                BigEndian.encodeUint64(account.getNonce()),
                BigEndian.encodeUint64(account.getBalance()),
                BigEndian.encodeUint64(account.getIncubatecost()),
                BigEndian.encodeUint64(account.getMortgage()),
                BigEndian.encodeUint64(account.getVote()));
    }

    private static int compareAccounts(Account left, Account right) {
        if (left == null || right == null
                || left.getPubkeyHash() == null || right.getPubkeyHash() == null) {
            throw new IllegalArgumentException("account and pubkeyHash must not be null");
        }
        return java.util.Arrays.compareUnsigned(left.getPubkeyHash(), right.getPubkeyHash());
    }

    private static int comparePaymentStates(PaymentStateEntry left, PaymentStateEntry right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("payment state entry must not be null");
        }
        int kind = Integer.compare(left.getKind().getCode(), right.getKind().getCode());
        if (kind != 0) {
            return kind;
        }
        return java.util.Arrays.compareUnsigned(
                left.getId().getBytes(StandardCharsets.UTF_8),
                right.getId().getBytes(StandardCharsets.UTF_8));
    }
}

package org.nexus.core;

import org.nexus.core.account.Account;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Atomic account and payment-state effects produced by one transaction. */
public final class PaymentExecutionResult {

    private final List<Account> accounts;
    private final List<PaymentStateEntry> paymentStates;
    private final long executedFee;

    public PaymentExecutionResult(List<Account> accounts, List<PaymentStateEntry> paymentStates,
                                  long executedFee) {
        if (accounts == null || paymentStates == null || executedFee < 0) {
            throw new IllegalArgumentException("invalid payment execution result");
        }
        this.accounts = Collections.unmodifiableList(new ArrayList<>(accounts));
        this.paymentStates = Collections.unmodifiableList(new ArrayList<>(paymentStates));
        this.executedFee = executedFee;
    }

    public List<Account> getAccounts() {
        return accounts;
    }

    public List<PaymentStateEntry> getPaymentStates() {
        return paymentStates;
    }

    public long getExecutedFee() {
        return executedFee;
    }
}

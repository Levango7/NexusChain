package org.nexus.common;

public interface PendingTransactionValidator {
    ValidateResult validate(Transaction transaction);
}

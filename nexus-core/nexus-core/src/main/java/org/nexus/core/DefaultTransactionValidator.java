package org.nexus.core;

import org.nexus.core.account.Transaction;
import org.nexus.core.validate.Result;
import org.nexus.core.validate.TransactionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Default implementation that composes all Spring-managed TransactionRule beans.
 * Rules are executed in order; first failure short-circuits.
 */
@Component
public class DefaultTransactionValidator implements TransactionValidator {

    private static final Logger log = LoggerFactory.getLogger(DefaultTransactionValidator.class);

    private final List<TransactionRule> rules;

    public DefaultTransactionValidator(List<TransactionRule> rules) {
        this.rules = rules != null ? rules : Collections.emptyList();
        log.info("TransactionValidator initialized with {} rules", this.rules.size());
    }

    @Override
    public Result validate(Transaction transaction) {
        if (transaction == null) {
            return Result.Error("transaction is null");
        }
        for (TransactionRule rule : rules) {
            Result result = rule.validateTransaction(transaction);
            if (!result.isSuccess()) {
                log.debug("Transaction {} failed rule {}: {}",
                        transaction.getHashHexString(), rule.getClass().getSimpleName(), result.getMessage());
                return result;
            }
        }
        return Result.SUCCESS;
    }

    @Override
    public List<TransactionRule> getRules() {
        return Collections.unmodifiableList(rules);
    }
}
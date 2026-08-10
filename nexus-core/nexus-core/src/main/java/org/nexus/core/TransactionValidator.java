package org.nexus.core;

import org.nexus.core.account.Transaction;
import org.nexus.core.validate.Result;
import org.nexus.core.validate.TransactionRule;

import java.util.List;

/**
 * Transaction validation facade.
 * Composes all registered TransactionRule implementations (SignatureRule, BasicRule, etc.)
 * and provides a single entry point for validating transactions before pool admission.
 */
public interface TransactionValidator {

    /**
     * Validate a transaction against all registered rules.
     * Returns SUCCESS if all rules pass, or the first failing Result.
     */
    Result validate(Transaction transaction);

    /**
     * Get the list of active validation rules.
     */
    List<TransactionRule> getRules();
}
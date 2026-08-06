package org.nexus.wallet.wallet.approval;

import java.math.BigDecimal;

/**
 * Approval policy interface defining the number of approvers required and
 * address whitelist checks for a withdrawal.
 */
public interface ApprovalPolicy {

    /**
     * Number of approvers required to release a withdrawal of the given
     * amount and currency.
     *
     * @param amount   withdrawal amount
     * @param currency currency symbol
     * @return number of required approvers (>= 1)
     */
    int getRequiredApprovers(BigDecimal amount, String currency);

    /**
     * Whether the given address is on the withdrawal whitelist.
     *
     * @param address wallet address
     * @return {@code true} if the address is whitelisted
     */
    boolean isAddressWhitelisted(String address);
}
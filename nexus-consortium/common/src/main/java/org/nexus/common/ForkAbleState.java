package org.nexus.common;

import java.util.Set;

/**
 * account related object
 * when implements as user account, the getIdentifier should return the wallet address of the user
 * and getIdentifiersOf returns from & to address of the transaction
 */
public interface ForkAbleState<T> extends State<T> {
    String getIdentifier();

    Set<String> getIdentifiersOf(Transaction transaction);

    T createEmpty(String id);
}

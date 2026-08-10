package org.nexus.crypto;

public interface KeyPair {
    PrivateKey getPrivateKey();
    PublicKey getPublicKey();
}
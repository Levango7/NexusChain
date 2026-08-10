package org.nexus.consortium.account;

import org.apache.commons.codec.binary.Hex;

import java.util.Optional;

import static org.nexus.consortium.ApplicationConstants.PUBLIC_KEY_SIZE;
import static org.nexus.consortium.account.Utils.*;

public class PublicKeyHash {
    private byte[] publicKeyHash;
    private String address;
    private String hex;
    public static PublicKeyHash fromPublicKey(byte[] publicKey){
        return new PublicKeyHash(publicKeyToHash(publicKey));
    }

    public static Optional<PublicKeyHash> from(String hex){
        byte[] publicKeyHash;
        try {
            publicKeyHash = Hex.decodeHex(hex);
            if (publicKeyHash.length == PUBLIC_KEY_SIZE) {
                return Optional.of(fromPublicKey(publicKeyHash));
            }
        } catch (Exception e) {
            return addressToPublicKeyHash(hex).map(PublicKeyHash::new);
        }
        return Optional.empty();
    }

    public PublicKeyHash(byte[] publicKeyHash) {
        this.publicKeyHash = publicKeyHash;
    }

    public String getAddress(){
        if (address == null) address = publicKeyHashToAddress(publicKeyHash);
        return address;
    }

    public byte[] getPublicKeyHash() {
        return publicKeyHash;
    }

    public String getHex(){
        if (hex == null) hex = Hex.encodeHexString(publicKeyHash);
        return hex;
    }
}

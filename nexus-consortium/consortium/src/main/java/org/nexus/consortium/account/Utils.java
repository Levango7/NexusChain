package org.nexus.consortium.account;

import com.google.common.primitives.Bytes;
import org.nexus.crypto.Base58Utility;
import org.nexus.crypto.HashFunctions;

import java.util.Arrays;
import java.util.Optional;

public class Utils {
    public static byte[] publicKeyToHash(byte[] publicKey) {
        return HashFunctions.ripemd160(HashFunctions.keccak256(publicKey));
    }

    public static String publicKeyToAddress(byte[] publicKey) {
        return publicKeyHashToAddress(publicKeyToHash(publicKey));
    }

    public static String publicKeyHashToAddress(byte[] publicKeyHash) {
        byte[] r2 = Bytes.concat(new byte[1], publicKeyHash);
        byte[] r3 = HashFunctions.keccak256(HashFunctions.keccak256(publicKeyHash));
        byte[] b4 = Arrays.copyOfRange(r3, 0, 4);
        byte[] b5 = Bytes.concat(r2, b4);
        return Base58Utility.encode(b5);
    }


    public static Optional<byte[]> addressToPublicKeyHash(String address) {
        if (!verifyAddress(address)){
            return Optional.empty();
        }
        return Optional.of(Arrays.copyOfRange(Base58Utility.decode(address), 1, 21));
    }

    private static boolean verifyAddress(String address) {
        if (!address.startsWith("1")) {//地址不是以"1"开头
            return false;
        }
        byte[] r5 = Base58Utility.decode(address);
        byte[] r3 = HashFunctions.keccak256(
                HashFunctions.keccak256(
                        Arrays.copyOfRange(Base58Utility.decode(address), 1, 21)
                )
        );
        byte[] b4 = Arrays.copyOfRange(r3, 0, 4);
        byte[] _b4 = Arrays.copyOfRange(r5, r5.length - 4, r5.length);
        //正确
        return Arrays.equals(b4, _b4);
    }
}



package org.nexus.crypto.ed25519;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.math.BigInteger;
import java.security.SecureRandom;

public class Ed25519 {
    public static final String ALGORITHM = "ed25519";

    public static final BigInteger MAX_PRIVATE_KEY = new BigInteger("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ec", 16);

    public static String getAlgorithm() {
        return ALGORITHM;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @return Ed25519 keypair for signature and verifying
     */
    public static Ed25519KeyPair generateKeyPair() {
        while (true) {
            Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(RANDOM);
            if (new BigInteger(1, privateKey.getEncoded()).compareTo(MAX_PRIVATE_KEY) > 0) {
                continue;
            }
            Ed25519PublicKeyParameters publicKey = privateKey.generatePublicKey();
            Ed25519KeyPair nkp = new Ed25519KeyPair();
            nkp.setPrivateKey(new Ed25519PrivateKey(privateKey));
            nkp.setPublicKey(new Ed25519PublicKey(publicKey));
            return nkp;
        }
    }
}

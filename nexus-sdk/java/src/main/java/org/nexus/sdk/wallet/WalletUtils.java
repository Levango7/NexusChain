package org.nexus.sdk.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.primitives.Bytes;
import org.apache.commons.codec.binary.Hex;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.crypto.ed25519.Ed25519PublicKey;
import org.nexus.keystore.account.Address;
import org.nexus.keystore.crypto.AESManage;
import org.nexus.keystore.crypto.ArgonManage;
import org.nexus.keystore.crypto.KeyPair;
import org.nexus.keystore.crypto.PublicKey;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.util.Base58Utility;
import org.nexus.keystore.util.ByteUtils;
import org.nexus.keystore.util.JsonUtils;
import org.nexus.keystore.util.Utils;
import org.nexus.keystore.wallet.Cipherparams;
import org.nexus.keystore.wallet.Crypto;
import org.nexus.keystore.wallet.Kdfparams;
import org.nexus.keystore.wallet.Keystore;
import org.nexus.keystore.wallet.KeystoreAction;
import org.nexus.util.ByteUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Jackson-based rewrite of the legacy {@code com.company.keystore.wallet.WalletUtility}.
 *
 * <p>All fastjson types ({@code com.alibaba.fastjson.JSON}/{@code JSONObject}) are replaced
 * with Jackson equivalents ({@link JsonNode}/{@link ObjectNode}). The underlying crypto
 * operations reuse {@code org.nexus.keystore.*} from nexus-core, eliminating the
 * nexus-java-sdk / wcli.jar dependency entirely.</p>
 */
public class WalletUtils {

    public String address;
    public Crypto crypto;
    private static final int saltLength = 32;
    private static final int ivLength = 16;
    private static final String defaultVersion = "1";
    private static final String t = "1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ec";
    public static byte[] outscrip;
    private static final Long rate = 100000000L;

    /**
     * Parse a keystore JSON string into a {@link Keystore} bean.
     * Replaces {@code Gson.fromJson} with Jackson {@code ObjectMapper.readValue}.
     */
    public static Keystore unmarshal(String in) {
        return JsonUtils.fromJson(in, Keystore.class);
    }

    /**
     * Serialize a {@link Keystore} bean to JSON string.
     * Replaces {@code Gson.toJson} with Jackson {@code ObjectMapper.writeValueAsString}.
     */
    public static String marshal(Keystore keystore) {
        return JsonUtils.toJson(keystore);
    }

    /**
     * Generate a keystore from a password.
     *
     * <p>fastjson {@code JSON.parseObject}/{@code JSON.toJSONString} → Jackson
     * {@code JsonUtils.readTree}/{@code JsonUtils.toJson}. Returns an empty
     * {@link ObjectNode} on invalid password or failure (preserving legacy semantics).</p>
     */
    public static ObjectNode fromPassword(String password) {
        try {
            if (password.length() > 20 || password.length() < 8) {
                return JsonUtils.createObjectNode();
            }
            KeyPair keyPair = KeyPair.generateEd25519KeyPair();
            PublicKey publicKey = keyPair.getPublicKey();
            byte[] salt = new byte[saltLength];
            byte[] iv = new byte[ivLength];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            SecureRandom sr = new SecureRandom();
            sr.nextBytes(salt);
            ArgonManage argon2id = new ArgonManage(ArgonManage.Type.ARGON2id, salt);
            AESManage aes = new AESManage(iv);

            byte[] derivedKey = argon2id.hash(password.getBytes());
            byte[] cipherPrivKey = aes.encrypt(derivedKey, keyPair.getPrivateKey().getEncoded());
            byte[] mac = SHA3Utility.keccak256(Bytes.concat(derivedKey, cipherPrivKey));
            Crypto crypto = new Crypto(
                    AESManage.cipher, Hex.encodeHexString(cipherPrivKey),
                    new Cipherparams(Hex.encodeHexString(iv))
            );
            Kdfparams kdfparams = new Kdfparams(
                    ArgonManage.memoryCost, ArgonManage.timeCost, ArgonManage.parallelism,
                    Hex.encodeHexString(salt)
            );

            Address ads = new Address(publicKey);
            Keystore ks = new Keystore(ads.getAddress(), crypto, Utils.generateUUID(),
                    defaultVersion, Hex.encodeHexString(mac), argon2id.kdf(), kdfparams
            );
            String jsonString = JsonUtils.toJson(ks);
            return (ObjectNode) JsonUtils.readTree(jsonString);
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    /**
     * Generate a keystore file on disk.
     *
     * <p>fastjson {@code JSONObject.toJSONString}/{@code JSON.parseObject} → Jackson
     * {@code JsonUtils.toJson}/{@code JsonUtils.readTree}. The nested crypto/cipherparams
     * are embedded as string-valued fields to preserve the legacy on-disk format.</p>
     */
    public static String generateKeystore(String password, String path) {
        try {
            String folderPath = path;
            if (folderPath == null || folderPath.isEmpty()) {
                folderPath = System.getProperty("user.dir") + File.separator + "Keystore";
            }
            File folder = new File(folderPath);
            if (!folder.exists()) {
                folder.mkdirs();
            }
            Keystore ks = KeystoreAction.fromPassword(password);
            Crypto crypto = ks.crypto;
            Cipherparams cipherparams = crypto.cipherparams;
            String filePath = folderPath + File.separator + ks.address;
            File file = new File(filePath);
            file.createNewFile();

            // Build the nested JSON structure with Jackson (legacy format: crypto/cipherparams as string values)
            ObjectNode ksNode = (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(ks));
            ObjectNode cryptoNode = (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(crypto));
            ObjectNode cipherparamsNode = (ObjectNode) JsonUtils.readTree(JsonUtils.toJson(cipherparams));
            cryptoNode.put("cipherparams", cipherparamsNode.toString());
            ksNode.put("crypto", cryptoNode.toString());
            String str = ksNode.toString();

            try (FileWriter fw = new FileWriter(file.getAbsoluteFile());
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write(str);
            }
            return ks.address;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Modify the keystore password.
     *
     * <p>fastjson → Jackson. Re-encrypts the private key under the new password
     * and returns the new keystore as {@link ObjectNode}.</p>
     */
    public static ObjectNode modifyPassword(String keystoreJson, String password, String newPassword) {
        try {
            String prikey = obtainPrikey(keystoreJson, password);
            Ed25519PrivateKey privateKey = new Ed25519PrivateKey(Hex.decodeHex(prikey.toCharArray()));
            Ed25519PublicKey publicKey = privateKey.generatePublicKey();
            if (password.length() > 20 || password.length() < 8) {
                return JsonUtils.createObjectNode();
            }
            byte[] salt = new byte[saltLength];
            byte[] iv = new byte[ivLength];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            SecureRandom sr = new SecureRandom();
            sr.nextBytes(salt);
            ArgonManage argon2id = new ArgonManage(ArgonManage.Type.ARGON2id, salt);
            AESManage aes = new AESManage(iv);

            byte[] derivedKey = argon2id.hash(newPassword.getBytes());
            byte[] cipherPrivKey = aes.encrypt(derivedKey, privateKey.getEncoded());
            byte[] mac = SHA3Utility.keccak256(Bytes.concat(derivedKey, cipherPrivKey));

            Crypto crypto = new Crypto(
                    AESManage.cipher, Hex.encodeHexString(cipherPrivKey),
                    new Cipherparams(Hex.encodeHexString(iv))
            );
            Kdfparams kdfparams = new Kdfparams(
                    ArgonManage.memoryCost, ArgonManage.timeCost, ArgonManage.parallelism,
                    Hex.encodeHexString(salt)
            );

            Address ads = new Address(publicKey);
            Keystore ks = new Keystore(ads.getAddress(), crypto, Utils.generateUUID(),
                    defaultVersion, Hex.encodeHexString(mac), argon2id.kdf(), kdfparams
            );
            String jsonString = JsonUtils.toJson(ks);
            return (ObjectNode) JsonUtils.readTree(jsonString);
        } catch (Exception e) {
            return JsonUtils.createObjectNode();
        }
    }

    /**
     * Convert a public-key hash (hex) to a Base58 address.
     * Logic: prepend 0x00 → double keccak256 → first 4 bytes → append → base58 encode.
     */
    public static String pubkeyHashToAddress(String r1Str) {
        try {
            byte[] r1 = Hex.decodeHex(r1Str.toCharArray());
            byte[] r2 = ByteUtil.prepend(r1, (byte) 0x00);
            byte[] r3 = SHA3Utility.keccak256(SHA3Utility.keccak256(r1));
            byte[] b4 = ByteUtil.bytearraycopy(r3, 0, 4);
            byte[] b5 = ByteUtil.byteMerger(r2, b4);
            return Base58Utility.encode(b5);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Convert a Base58 address to a public-key hash (hex).
     * Logic: base58 decode → take first 21 bytes → skip 1-byte version → 20-byte hash.
     */
    public static String addressToPubkeyHash(String address) {
        try {
            byte[] r5 = Base58Utility.decode(address);
            byte[] r2 = ByteUtil.bytearraycopy(r5, 0, 21);
            byte[] r1 = ByteUtil.bytearraycopy(r2, 1, 20);
            return Hex.encodeHexString(r1);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extract the address from a keystore JSON.
     * fastjson {@code JSON.parseObject(ksJson, Keystore.class)} → Jackson {@code JsonUtils.fromJson}.
     */
    public static String keystoreToAddress(String ksJson, String password) {
        try {
            Keystore ks = JsonUtils.fromJson(ksJson, Keystore.class);
            return ks.address;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extract the public key from a keystore JSON + password.
     */
    public static String keystoreToPubkey(String ksJson, String password) {
        try {
            Keystore ks = JsonUtils.fromJson(ksJson, Keystore.class);
            String privateKey = KeystoreAction.obtainPrikey(ks, password);
            return KeystoreAction.prikeyToPubkey(privateKey);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extract the public-key hash from a keystore JSON + password.
     */
    public static String keystoreToPubkeyHash(String ksJson, String password) {
        try {
            Keystore ks = JsonUtils.fromJson(ksJson, Keystore.class);
            String privateKey = KeystoreAction.obtainPrikey(ks, password);
            String pubkey = KeystoreAction.prikeyToPubkey(privateKey);
            byte[] pub256 = SHA3Utility.keccak256(Hex.decodeHex(pubkey.toCharArray()));
            byte[] r1 = RipemdUtility.ripemd160(pub256);
            return Hex.encodeHexString(r1);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Decrypt and return the private key (hex) from a keystore JSON + password.
     */
    public static String obtainPrikey(String ksJson, String password) {
        try {
            Keystore ks = JsonUtils.fromJson(ksJson, Keystore.class);
            return Hex.encodeHexString(KeystoreAction.decrypt(ks, password));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Derive the public key from a private key (hex).
     * Includes the legacy length/range validation against the curve order {@code t}.
     */
    public static String prikeyToPubkey(String prikey) {
        try {
            if (prikey.length() != 64 ||
                    new BigInteger(Hex.decodeHex(prikey.toCharArray()))
                            .compareTo(new BigInteger(ByteUtils.hexStringToBytes(t))) > 0) {
                return "";
            }
            Ed25519PrivateKey eprik = new Ed25519PrivateKey(Hex.decodeHex(prikey.toCharArray()));
            Ed25519PublicKey epuk = eprik.generatePublicKey();
            return Hex.encodeHexString(epuk.getEncoded());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Convert a public-key string (hex) to a public-key hash string (hex).
     * Logic: keccak256 → ripemd160 → hex.
     */
    public static String pubkeyStrToPubkeyHashStr(String pubkeyStr) {
        try {
            byte[] pubkey = Hex.decodeHex(pubkeyStr.toCharArray());
            byte[] pub256 = SHA3Utility.keccak256(pubkey);
            byte[] r1 = RipemdUtility.ripemd160(pub256);
            return Hex.encodeHexString(r1);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Validate a Base58 address.
     * @return 0 if valid, -1 if not starting with "1", -2 if checksum mismatch or error.
     */
    public static int verifyAddress(String address) {
        try {
            byte[] r5 = Base58Utility.decode(address);
            if (!address.startsWith("1")) {
                return -1;
            }
            byte[] r3 = SHA3Utility.keccak256(SHA3Utility.keccak256(KeystoreAction.addressToPubkeyHash(address)));
            byte[] b4 = ByteUtil.bytearraycopy(r3, 0, 4);
            byte[] _b4 = ByteUtil.bytearraycopy(r5, r5.length - 4, 4);
            if (Arrays.equals(b4, _b4)) {
                return 0;
            } else {
                return -2;
            }
        } catch (Exception e) {
            return -2;
        }
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
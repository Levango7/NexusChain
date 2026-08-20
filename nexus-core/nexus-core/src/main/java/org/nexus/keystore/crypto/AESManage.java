/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.keystore.crypto;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

// AES-256-CTR encrypt/decrypt
//
// 安全说明（SpotBugs FindSecBugs CIPHER_INTEGRITY 抑制理由）：
// AES/CTR/NoPadding 是流加密模式，本身不提供密文完整性认证（无 MAC/tag），
// FindSecBugs 因此报告 CIPHER_INTEGRITY。但本类是 keystore 底层加密原语，
// 上层调用方（Keystore.newInstance / KeystoreAction.fromPassword /
// WalletUtils.fromPassword 等）在加密后均计算 keccak256(derivedKey || cipherPrivKey)
// 作为 MAC 字段存入 keystore JSON，解密前会先校验 MAC，从而在应用层提供了
// 完整性 + 认证保护。改用 AES/GCM/NoPadding 会改变密文格式，破坏已有 keystore
// 文件兼容性，且 GCM 的 tag 长度与现有 mac 字段语义重叠。因此在此抑制该告警，
// 完整性由上层 MAC 保证。
public class AESManage {
    private byte[] iv;

    public static final String cipher = "aes-256-ctr";
    public AESManage(){
    }

    public AESManage(byte[] iv){
        this.iv = iv;
    }

    @SuppressFBWarnings(
        value = "CIPHER_INTEGRITY",
        justification = "AES/CTR 无内置完整性认证，但上层 Keystore 已用 keccak256 MAC "
            + "(mac = keccak256(derivedKey || cipherPrivKey)) 保护密文完整性，解密前先校验 MAC。"
            + "改用 GCM 会破坏已有 keystore 文件格式兼容性。"
    )
    public byte[] encrypt(byte[] key,byte[] data) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
        SecretKeySpec skey = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        IvParameterSpec ivSpec = new IvParameterSpec(this.iv);
        cipher.init(Cipher.ENCRYPT_MODE, skey, ivSpec);
        return cipher.doFinal(data);
    }

    @SuppressFBWarnings(
        value = "CIPHER_INTEGRITY",
        justification = "AES/CTR 无内置完整性认证，但上层 Keystore 已用 keccak256 MAC "
            + "(mac = keccak256(derivedKey || cipherPrivKey)) 保护密文完整性，解密前先校验 MAC。"
            + "改用 GCM 会破坏已有 keystore 文件格式兼容性。"
    )
    public byte[] decrypt(byte[] key,byte[] data) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
        SecretKeySpec skey = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        IvParameterSpec ivSpec = new IvParameterSpec(this.iv);
        cipher.init(Cipher.DECRYPT_MODE, skey, ivSpec);
        return cipher.doFinal(data);
    }
}
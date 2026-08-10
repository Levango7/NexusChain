package org.nexus.db;

import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.nexus.keystore.account.Address;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.wallet.KeystoreAction;

import java.util.*;

public class Utils {
    public static List<byte[]> getAllPublicKeyHashes(Block b){
        Set<byte[]> res = new HashSet<>();
        if(b.body == null){
            return new ArrayList<>();
        }
        for(Transaction tx: b.body){
            if(!Arrays.equals(tx.from, new byte[tx.from.length])){
                res.add(RipemdUtility.ripemd160(SHA3Utility.keccak256(tx.from)));
            }
            if(!Arrays.equals(tx.to, new byte[tx.to.length])){
                res.add(tx.to);
            }
        }
        // 添加孵化总地址
        return new ArrayList<>(res);
    }
}

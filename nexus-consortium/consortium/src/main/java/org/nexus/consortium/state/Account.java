package org.nexus.consortium.state;

import com.google.common.primitives.Bytes;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.nexus.common.*;
import org.nexus.consortium.account.PublicKeyHash;
import org.nexus.consortium.account.Utils;
import org.nexus.consortium.util.BytesReader;
import org.nexus.exception.StateUpdateException;
import org.nexus.util.BigEndian;

import java.util.HashSet;
import java.util.Set;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class Account implements ForkAbleState<Account>, Serializable, Deserializable {
    private PublicKeyHash publicKeyHash;

    private long balance;

    @Override
    public String getIdentifier() {
        return publicKeyHash.getAddress();
    }

    @Override
    public Set<String> getIdentifiersOf(Transaction transaction) {
        Set<String> res = new HashSet<>();
        res.add(Utils.publicKeyToAddress(transaction.getFrom().getBytes()));
        res.add(Utils.publicKeyHashToAddress(transaction.getTo().getBytes()));
        return res;
    }

    @Override
    public Account createEmpty(String id) {
        return new Account(PublicKeyHash.from(id).get(), 0);
    }

    @Override
    public void update(Header h) throws StateUpdateException {

    }

    @Override
    public void update(Block b, Transaction t) throws StateUpdateException {
        if (Utils.publicKeyToAddress(t.getFrom().getBytes()).equals(publicKeyHash.getAddress())){
            balance -= t.getAmount();
        }
        if (Utils.publicKeyHashToAddress(t.getTo().getBytes()).equals(publicKeyHash.getAddress())){
            balance += t.getAmount();
        }
    }

    @Override
    public Account clone() {
        return new Account(publicKeyHash, balance);
    }

    @Override
    public void copyFrom(byte[] data) {
        BytesReader reader = new BytesReader(data);
        publicKeyHash = new PublicKeyHash(reader.read(32));
        balance = BigEndian.decodeInt64(reader.readAll());
    }

    @Override
    public byte[] getBytes() {
        return Bytes.concat(publicKeyHash.getPublicKeyHash(),BigEndian.encodeInt64(balance));
    }
}

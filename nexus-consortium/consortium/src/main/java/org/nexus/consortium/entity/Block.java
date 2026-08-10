package org.nexus.consortium.entity;


import lombok.*;
import org.nexus.consortium.util.ByteUtil;
import org.nexus.consortium.util.RLPElement;
import org.nexus.consortium.util.RLPList;
import org.nexus.consortium.util.RLPUtils;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = HeaderAdapter.TABLE_HEADER)
public class Block extends HeaderAdapter {
    @OneToMany(cascade = CascadeType.ALL,
            fetch = FetchType.EAGER)
    @JoinColumn(
            name = Transaction.COLUMN_BLOCK_HASH,
            referencedColumnName = HeaderAdapter.COLUMN_HASH,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)
    )
    @Getter
    @Setter
    List<Transaction> body;

    public Block() {
    }

    public Block(HeaderAdapter adapter){
        super(adapter.getHash(), adapter.getVersion(), adapter.getHashPrev(), adapter.getMerkleRoot(), adapter.getHeight(), adapter.getCreatedAt(), adapter.getPayload());
    }

    public Block(byte[] hash, int version, byte[] hashPrev, byte[] merkleRoot, long height, long createdAt, byte[] payload) {
        super(hash, version, hashPrev, merkleRoot, height, createdAt, payload);
    }

    public byte[] encode(){
        byte[] hash= RLPUtils.encodeElement(this.getHash());
        byte[] version=RLPUtils.encodeInt(this.getVersion());
        byte[] hashPrev=RLPUtils.encodeElement(this.getHashPrev());
        byte[] merkleRoot=RLPUtils.encodeElement(this.getMerkleRoot());
        byte[] height=RLPUtils.encodeElement(ByteUtil.longToBytes(this.getHeight()));
        byte[] createdAt=RLPUtils.encodeElement(ByteUtil.longToBytes(this.getCreatedAt()));
        byte[] payload=RLPUtils.encodeElement(this.getPayload());
        byte[][] bodybyte = new byte[body.size()][];
        for(int x=0;x<body.size();x++){
            bodybyte[x]=RLPUtils.encodeList(
                    body.get(x).encode()
            );
        }
        byte[] tranlist=RLPUtils.encodeList(bodybyte);

        return RLPUtils.encodeList(hash,version,hashPrev,merkleRoot,height,createdAt,payload,tranlist);
    }

    public static Block decode(byte[] Data){
        Block block=new Block();
        try{
            RLPList paramsList = (RLPList) RLPUtils.decode2(Data).get(0);
            block.setHash(paramsList.get(0).getRLPBytes());
            block.setVersion(paramsList.get(1).getRLPInt());
            block.setHashPrev(paramsList.get(2).getRLPBytes());
            block.setMerkleRoot(paramsList.get(3).getRLPBytes());
            block.setHeight(paramsList.get(4).getRLPLong());
            block.setCreatedAt(paramsList.get(5).getRLPLong());
            block.setPayload(paramsList.get(6).getRLPBytes());
            List<Transaction> transactionList=new ArrayList<>();
            RLPList tranList= (RLPList) paramsList.get(7);
            for(Object tranlist:tranList){
                RLPElement capId = ((RLPList) tranlist).get(0);
                transactionList.add(Transaction.decode(capId.getRLPBytes()));
            }
            return block;
        }catch (Exception e){
            return null;
        }
    }
}

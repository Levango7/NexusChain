package org.nexus.consortium.entity;

import lombok.*;
import org.nexus.consortium.util.ByteUtil;
import org.nexus.consortium.util.RLPList;
import org.nexus.consortium.util.RLPUtils;

import jakarta.persistence.*;

@Entity
@Table(name = "transaction", indexes = {
        @Index(name = "tx_block_hash_index", columnList = Transaction.COLUMN_BLOCK_HASH),
        @Index(name = "tx_block_height_index", columnList = Transaction.COLUMN_BLOCK_HEIGHT),
        @Index(name = "tx_hash_index", columnList = Transaction.COLUMN_TX_HASH),
        @Index(name = "tx_type_index", columnList = Transaction.COLUMN_TX_TYPE),
        @Index(name = "tx_created_at_index", columnList = Transaction.COLUMN_TX_CREATED_AT),
        @Index(name = "tx_nonce_index", columnList = Transaction.COLUMN_TX_NONCE),
        @Index(name = "tx_from_index", columnList = Transaction.COLUMN_TX_FROM),
        @Index(name = "tx_amount_index", columnList = Transaction.COLUMN_TX_AMOUNT),
        @Index(name = "tx_to_index", columnList = Transaction.COLUMN_TX_TO),
        @Index(name = "tx_position_index", columnList = Transaction.COLUMN_TX_POSITION),
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {
    static final String COLUMN_BLOCK_HASH = "tx_block_hash";
    static final String COLUMN_BLOCK_HEIGHT = "tx_block_height";
    static final String COLUMN_TX_HASH = "tx_hash";
    static final String COLUMN_TX_VERSION = "tx_version";
    static final String COLUMN_TX_TYPE = "tx_type";
    static final String COLUMN_TX_CREATED_AT = "tx_created_at";
    static final String COLUMN_TX_NONCE = "tx_nonce";
    static final String COLUMN_TX_FROM = "tx_from";
    static final String COLUMN_TX_GAS_PRICE = "tx_gas_price";
    static final String COLUMN_TX_AMOUNT = "tx_amount";
    static final String COLUMN_TX_PAYLOAD = "tx_payload";
    static final String COLUMN_TX_TO = "tx_to";
    static final String COLUMN_TX_SIGNATURE = "tx_signature";
    static final String COLUMN_TX_POSITION = "tx_position";

    @Column(name = COLUMN_BLOCK_HASH, nullable = false)
    private byte[] blockHash;

    @Column(name = COLUMN_BLOCK_HEIGHT, nullable = false)
    private long height;

    @Id
    @Column(name = COLUMN_TX_HASH, nullable = false)
    private byte[] hash;

    @Column(name = COLUMN_TX_VERSION, nullable = false)
    private int version;

    @Column(name = COLUMN_TX_TYPE, nullable = false)
    private int type;

    @Column(name = COLUMN_TX_CREATED_AT, nullable = false)
    private long createdAt;

    @Column(name = COLUMN_TX_NONCE, nullable = false)
    private long nonce;

    @Column(name = COLUMN_TX_FROM, nullable = false)
    private byte[] from;

    @Column(name = COLUMN_TX_GAS_PRICE, nullable = false)
    private long gasPrice;

    @Column(name = COLUMN_TX_AMOUNT, nullable = false)
    private long amount;

    @Column(name = COLUMN_TX_PAYLOAD, nullable = false)
    public byte[] payload;

    @Column(name = COLUMN_TX_TO, nullable = false)
    private byte[] to;

    @Column(name = COLUMN_TX_SIGNATURE, nullable = false)
    private byte[] signature;

    @Column(name = COLUMN_TX_POSITION, nullable = false)
    private int position;

    private byte[] RLPbytes;

    public byte[] encode(){
        byte[] blockHash= RLPUtils.encodeElement(this.blockHash);
        byte[] height=RLPUtils.encodeElement(ByteUtil.longToBytes(this.height));
        byte[] hash=RLPUtils.encodeElement(this.hash);
        byte[] version=RLPUtils.encodeInt(this.version);
        byte[] type=RLPUtils.encodeInt(this.type);
        byte[] createdAt=RLPUtils.encodeElement(ByteUtil.longToBytes(this.createdAt));
        byte[] nonce=RLPUtils.encodeElement(ByteUtil.longToBytes(this.nonce));
        byte[] from=RLPUtils.encodeElement(this.from);
        byte[] gasPrice=RLPUtils.encodeElement(ByteUtil.longToBytes(this.gasPrice));
        byte[] amount=RLPUtils.encodeElement(ByteUtil.longToBytes(this.amount));
        byte[] payload=RLPUtils.encodeElement(this.payload);
        byte[] to=RLPUtils.encodeElement(this.to);
        byte[] signature=RLPUtils.encodeElement(this.signature);
        byte[] position=RLPUtils.encodeInt(this.position);

        this.RLPbytes=RLPUtils.encodeList(blockHash,height,hash,version,type,createdAt
            ,nonce,from,gasPrice,amount,payload,to,signature,position);
        return this.RLPbytes;
    }

    public static Transaction decode(byte[] Data){
        Transaction transaction=new Transaction();
        try{
            RLPList paramsList = (RLPList) RLPUtils.decode2(Data).get(0);
            transaction.setBlockHash(paramsList.get(0).getRLPBytes());
            transaction.setHeight(paramsList.get(1).getRLPLong());
            transaction.setHash(paramsList.get(2).getRLPBytes());
            transaction.setVersion(paramsList.get(3).getRLPInt());
            transaction.setType(paramsList.get(4).getRLPInt());
            transaction.setCreatedAt(paramsList.get(5).getRLPLong());
            transaction.setNonce(paramsList.get(6).getRLPLong());
            transaction.setFrom(paramsList.get(7).getRLPBytes());
            transaction.setGasPrice(paramsList.get(8).getRLPLong());
            transaction.setAmount(paramsList.get(9).getRLPLong());
            transaction.setPayload(paramsList.get(10).getRLPBytes());
            transaction.setTo(paramsList.get(11).getRLPBytes());
            transaction.setSignature(paramsList.get(12).getRLPBytes());
            transaction.setPosition(paramsList.get(13).getRLPByte());

            return transaction;
        }catch (Exception e){
            return null;
        }
    }

}

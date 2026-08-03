package org.nexus.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.*;
import org.nexus.util.EpochSecondDeserializer;
import org.nexus.util.EpochSecondsSerializer;

import java.util.stream.Stream;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction implements Cloneable<Transaction> {
    private HexBytes blockHash;

    private long height;

    private int version;

    private int type;

    @JsonSerialize(using = EpochSecondsSerializer.class)
    @JsonDeserialize(using = EpochSecondDeserializer.class)
    private long createdAt;

    private long nonce;

    private HexBytes from;

    private long gasPrice;

    private long amount;

    public HexBytes payload;

    private HexBytes to;

    private HexBytes signature;

    private HexBytes hash;

    @Override
    public Transaction clone() {
        return builder()
                .blockHash(blockHash).height(height)
                .version(version)
                .type(type).nonce(nonce)
                .createdAt(createdAt).from(from)
                .gasPrice(gasPrice).amount(amount)
                .payload(payload).to(to)
                .signature(signature).build();
    }


    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public int size() {
        return Constants.sizeOf(version) + Constants.sizeOf(type)
                + Constants.sizeOf(createdAt) + Constants.sizeOf(nonce)
                + Constants.sizeOf(gasPrice) + Constants.sizeOf(amount) +
                Stream.of(from, payload, to, signature)
                        .map(Constants::sizeOf)
                        .reduce(0, Integer::sum);
    }

}

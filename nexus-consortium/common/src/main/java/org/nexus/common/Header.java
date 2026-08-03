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
public class Header implements Cloneable<Header>, Chained {
    private int version;

    private HexBytes hashPrev;

    private HexBytes merkleRoot;

    private long height;

    @JsonSerialize(using = EpochSecondsSerializer.class)
    @JsonDeserialize(using = EpochSecondDeserializer.class)
    private long createdAt;

    private HexBytes payload;

    private HexBytes hash;

    @Override
    public Header clone() {
        return builder().version(version)
                .hashPrev(hashPrev).merkleRoot(merkleRoot)
                .height(height).createdAt(createdAt)
                .payload(payload).build();
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public int size() {
        return Constants.sizeOf(version) + Constants.sizeOf(height) +
                Constants.sizeOf(createdAt) +
                Stream.of(hashPrev, merkleRoot, payload, hash)
                        .map(Constants::sizeOf)
                        .reduce(0, Integer::sum);
    }
}

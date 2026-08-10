package org.nexus.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Delegate;
import org.nexus.util.EpochSecondDeserializer;
import org.nexus.util.EpochSecondsSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Block implements Cloneable<Block>, Chained{
    private static abstract class ExcludedMethods{
        public abstract Block clone();
        public abstract int size();
        public abstract long getCreatedAt();
    }

    // extend from header
    @Getter
    @JsonIgnore
    @Delegate(excludes = ExcludedMethods.class)
    private Header header;

    @Getter
    @Setter
    private List<Transaction> body;

    @JsonSerialize(using = EpochSecondsSerializer.class)
    @JsonDeserialize(using = EpochSecondDeserializer.class)
    public long getCreatedAt(){
        return header.getCreatedAt();
    }

    public Block(){
        header = new Header();
        body = new ArrayList<>();
    }

    public Block(@NonNull Header header){
        this.header = header;
        body = new ArrayList<>();
    }

    public Block clone() {
        Block b = new Block(header.clone());
        b.setBody(body.stream().map(Transaction::clone).collect(Collectors.toList()));
        return b;
    }

    // serialization only
    @JsonProperty(access = JsonProperty.Access.READ_ONLY, value = "size")
    public int size(){
        return header.size() + bodySize();
    }

    private int bodySize(){
        return body == null ? 0 : body.stream()
                .map(Transaction::size)
                .reduce(0, Integer::sum);
    }
}

package org.nexus.consortium.consensus.poa;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PoAConfig {
    private String name;
    private String genesis;
    @JsonProperty("block-interval")
    private int blockInterval;
    @JsonProperty("enable-mining")
    private boolean enableMining;
    @JsonProperty("miner-coin-base")
    private String minerCoinBase;
}

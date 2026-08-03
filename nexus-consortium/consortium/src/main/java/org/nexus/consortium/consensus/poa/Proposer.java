package org.nexus.consortium.consensus.poa;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Proposer {

    private String address;

    private long startTimeStamp;

    private long endTimeStamp;
}

package org.nexus.consortium;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Properties;

@ConfigurationProperties(prefix = "consortium.consensus")
@Component
public class ConsensusProperties extends Properties{
    static final String CONSENSUS_NAME = "name";
}


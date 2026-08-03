package org.nexus.consortium;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;

@ConfigurationProperties(prefix = "consortium")
@Component
public class GlobalConfig extends HashMap {
}

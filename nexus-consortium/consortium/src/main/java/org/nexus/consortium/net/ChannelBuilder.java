package org.nexus.consortium.net;

import java.util.Optional;

public interface ChannelBuilder {
    Optional<Channel> createChannel(String host, int port, Channel.ChannelListener... listeners);
}

package org.nexus.common;

public interface Peer {
    String getHost();

    int getPort();

    HexBytes getID();

    String encodeURI();
}

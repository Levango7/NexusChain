package org.nexus.common;

import java.util.Collection;

// context for communicating with peer server and plugin
public interface Context {
    // disconnect to the peer
    void disconnect();

    // block the peer for a while
    void block();

    // keep the connection alive
    void keep();

    // response to the remote peer
    void response(byte[] message);

    // batch response
    void response(Collection<byte[]> messages);

    // relay the received message
    void relay();

    // get the message received from channel
    byte[] getMessage();

    // get remote peer
    Peer getRemote();
}

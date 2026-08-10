package org.nexus.consortium.net;

import lombok.Builder;
import org.nexus.common.Peer;
import org.nexus.consortium.proto.*;

import java.util.Collection;
import java.util.Collections;

@Builder
public class ContextImpl implements org.nexus.common.Context {
    boolean keep;
    boolean relay;
    boolean exit;
    boolean disconnect;
    boolean block;
    PeerImpl remote;
    Message message;
    Channel channel;
    MessageBuilder builder;


    public void exit() {
        exit = true;
    }

    @Override
    public void disconnect() {
        disconnect = true;
    }

    @Override
    public void block() {
        block = true;
    }

    @Override
    public void keep() {
        keep = true;
    }

    @Override
    public void response(byte[] message) {
        response(Collections.singleton(message));
    }

    @Override
    public void response(Collection<byte[]> messages) {
        if (block || disconnect || exit) return;
        for(byte[] msg: messages){
            channel.write(builder.buildAnother(msg));
        }
    }

    @Override
    public void relay() {
        relay = true;
    }

    @Override
    public byte[] getMessage() {
        return message.getBody().toByteArray();
    }

    @Override
    public Peer getRemote() {
        return remote;
    }
}

package org.nexus.consortium.net;

public interface  Plugin {
    void onMessage(ContextImpl context, ProtoPeerServer server);

    void onStart(ProtoPeerServer server);

    void onNewPeer(PeerImpl peer, ProtoPeerServer server);

    void onDisconnect(PeerImpl peer, ProtoPeerServer server);
}

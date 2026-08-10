package org.nexus.common;

public interface PeerServerListener {
    // triggered when new message received
    void onMessage(Context context, PeerServer server);

    // triggered when server starts, you could run scheduled task here
    // you could set the PeerServer as your member here
    void onStart(PeerServer server);

    // triggered when a new peer connected
    void onNewPeer(Peer peer, PeerServer server);

    // triggered when a peer disconnected
    void onDisconnect(Peer peer, PeerServer server);
}

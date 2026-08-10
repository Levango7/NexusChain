package org.nexus.consortium.net;

import org.nexus.common.PeerServerListener;
import org.nexus.consortium.proto.Code;

public class PluginWrapper implements Plugin{
    private PeerServerListener listener;

    public PluginWrapper(PeerServerListener listener) {
        this.listener = listener;
    }

    @Override
    public void onMessage(ContextImpl context, ProtoPeerServer server) {
        if(context.message.getCode().equals(Code.ANOTHER)){
            listener.onMessage(context, server);
        }
    }

    @Override
    public void onStart(ProtoPeerServer server) {
        listener.onStart(server);
    }

    @Override
    public void onNewPeer(PeerImpl peer, ProtoPeerServer server) {
        listener.onNewPeer(peer, server);
    }

    @Override
    public void onDisconnect(PeerImpl peer, ProtoPeerServer server) {
        listener.onDisconnect(peer, server);
    }
}

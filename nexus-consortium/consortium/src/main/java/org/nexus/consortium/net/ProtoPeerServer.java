package org.nexus.consortium.net;

import org.nexus.common.PeerServer;

public interface ProtoPeerServer extends PeerServer {
    Client getClient();
}

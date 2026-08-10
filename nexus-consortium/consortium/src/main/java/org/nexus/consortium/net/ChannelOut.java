package org.nexus.consortium.net;

import org.nexus.consortium.proto.Message;

interface ChannelOut {
    void write(Message message);
    void close();
}

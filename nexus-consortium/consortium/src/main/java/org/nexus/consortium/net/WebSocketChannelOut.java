package org.nexus.consortium.net;

import lombok.AllArgsConstructor;
import org.java_websocket.WebSocket;
import org.nexus.consortium.proto.Message;

@AllArgsConstructor
public class WebSocketChannelOut implements ChannelOut{
    private WebSocket conn;

    @Override
    public void write(Message message) {
        try {
            conn.send(message.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

package org.nexus.consortium.net;

import lombok.AllArgsConstructor;
import org.java_websocket.WebSocket;
import org.nexus.consortium.proto.Message;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class WebSocketChannelOut implements ChannelOut{
    private WebSocket conn;

    @Override
    public void write(Message message) {
        try {
            conn.send(message.toByteArray());
        } catch (Exception e) {
            log.error("WebSocketChannelOut: uncaught exception", e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (Exception e) {
            log.error("WebSocketChannelOut: uncaught exception", e);
        }
    }
}

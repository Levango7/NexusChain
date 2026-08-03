package org.nexus.consortium.net;

import com.google.protobuf.InvalidProtocolBufferException;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.nexus.consortium.proto.Message;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class WebSocketPeerServer extends AbstractPeerServer {
    private static class Server extends WebSocketServer {
        final Map<WebSocket, ProtoChannel> channels = new ConcurrentHashMap<>();

        private Client client;
        private WebSocketPeerServer server;

        public Server(int port, Client client, WebSocketPeerServer server) {
            super(new InetSocketAddress(port));
            this.client = client;
            this.server = server;
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            ProtoChannel ch = new ProtoChannel();
            ch.setOut(new WebSocketChannelOut(conn));
            ch.addListener(client, server);
            channels.put(conn, ch);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            ProtoChannel ch = channels.get(conn);
            if (ch == null) return;
            ch.close();
            channels.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            ProtoChannel ch = channels.get(conn);
            if (ch == null) return;
            try {
                Message msg = Message.parseFrom(message);
                ch.message(msg);
            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
            }

        }

        @Override
        public void onMessage(WebSocket conn, String message) {

        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            ProtoChannel ch = channels.get(conn);
            if (ch == null) return;
            ch.error(ex);
        }

        @Override
        public void onStart() {

        }
    }

    @Override
    void startListening() {
        Server s = new Server(self.getPort(), client, this);
        s.start();
    }

    @Override
    ChannelBuilder getChannelBuilder() {
        return new WebSocketChannelBuilder();
    }
}
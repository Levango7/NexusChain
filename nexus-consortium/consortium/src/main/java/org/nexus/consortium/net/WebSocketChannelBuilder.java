package org.nexus.consortium.net;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.nexus.consortium.proto.Message;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class WebSocketChannelBuilder implements ChannelBuilder {
    @Override
    public Optional<Channel> createChannel(String host, int port, Channel.ChannelListener... listeners) {
        try{
            ProtoChannel ch = new ProtoChannel();
            Client client = new Client(host, port, ch);
            client.getChannel().addListener(listeners);
            client.connectBlocking(1, TimeUnit.SECONDS);
            return Optional.of(client.getChannel());
        }catch (Exception e){
            e.printStackTrace();
            return Optional.empty();
        }
    }

    @AllArgsConstructor
    private static class WebSocketClientChannelOut implements ChannelOut{
        private Client client;

        @Override
        public void write(Message message) {
            client.send(message.toByteArray());
        }

        @Override
        public void close() {
            client.close();
        }
    }

    @Getter
    private static class Client extends WebSocketClient {
        private ProtoChannel channel;

        public Client(String host, int port, ProtoChannel channel) throws Exception {
            super(new URI(String.format("ws://%s:%d", host, port)));
            this.channel = channel;
            this.channel.setOut(new WebSocketClientChannelOut(this));
        }

        @Override
        public void onOpen(ServerHandshake ignored) {
        }

        @Override
        public void onMessage(ByteBuffer bytes) {
            try {
                this.channel.message(Message.parseFrom(bytes));
            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onMessage(String message) {
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            channel.close();
        }

        @Override
        public void onError(Exception ex) {
            channel.error(ex);
        }
    }
}

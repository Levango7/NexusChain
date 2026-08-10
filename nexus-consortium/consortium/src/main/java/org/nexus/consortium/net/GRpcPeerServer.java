package org.nexus.consortium.net;

import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import lombok.AllArgsConstructor;
import org.nexus.consortium.proto.EntryGrpc;
import org.nexus.consortium.proto.Message;

import java.io.IOException;

public class GRpcPeerServer extends AbstractPeerServer {
    @Override
    ChannelBuilder getChannelBuilder() {
        return new GRpcChannelBuilder();
    }

    @Override
    void startListening() {
        try {
            ServerBuilder.forPort(self.getPort()).addService(new EntryService(client, this)).build().start();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @AllArgsConstructor
    private static class EntryService  extends EntryGrpc.EntryImplBase{
        private Client client;
        private GRpcPeerServer server;

        @Override
        public StreamObserver<Message> entry(StreamObserver<Message> responseObserver) {
            ProtoChannel ch = new ProtoChannel();
            ch.setOut(new GRpcChannelOut(responseObserver));
            ch.addListener(client, server);
            return new ChannelWrapper(ch);
        }
    }

}

package org.nexus.consortium.net;

import io.grpc.stub.StreamObserver;
import lombok.AllArgsConstructor;
import org.nexus.consortium.proto.Message;

@AllArgsConstructor
public class ChannelWrapper implements StreamObserver<Message> {
    private Channel channel;

    @Override
    public void onNext(Message value) {
        channel.message(value);
    }

    @Override
    public void onError(Throwable t) {
        channel.error(t);
    }

    @Override
    public void onCompleted() {
        channel.close();
    }

    public void addListener(Channel.ChannelListener... listeners) {
        channel.addListener(listeners);
    }
}

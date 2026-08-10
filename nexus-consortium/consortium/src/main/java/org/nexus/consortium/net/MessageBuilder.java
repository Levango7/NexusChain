package org.nexus.consortium.net;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.nexus.common.Peer;
import org.nexus.consortium.proto.*;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.nexus.consortium.net.Util.getRawForSign;

public class MessageBuilder {
    private PeerImpl self;
    private AtomicLong nonce = new AtomicLong();

    public MessageBuilder(PeerImpl self) {
        this.self = self;
    }

    public Message buildNothing(){
        return buildMessage(Code.NOTHING, 1, Nothing.newBuilder().build().toByteArray());
    }

    public Message buildPing(){
        return buildMessage(Code.PING, 1, Ping.newBuilder().build().toByteArray());
    }

    public Message buildPong(){
        return buildMessage(Code.PONG, 1, Pong.newBuilder().build().toByteArray());
    }

    public Message buildLookup(){
        return buildMessage(Code.LOOK_UP, 1, Lookup.newBuilder().build().toByteArray());
    }

    public Message buildPeers(Collection<? extends Peer> peers){
        return buildMessage(Code.PEERS, 1, Peers
                .newBuilder().addAllPeers(
                    peers.stream().map(Peer::encodeURI).collect(Collectors.toList())
                )
                .build().toByteArray()
        );
    }

    public Message buildAnother(byte[] body){
        return buildMessage(Code.ANOTHER, 1, body);
    }

    public Message buildRelay(Message message) {
        Message.Builder builder = Message.newBuilder().mergeFrom(message)
                .setCreatedAt(
                        Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build()
                )
                .setRemotePeer(self.encodeURI())
                .setNonce(nonce.incrementAndGet())
                .setTtl(message.getTtl() - 1);
        byte[] sig = self.getPrivateKey().sign(getRawForSign(builder.build()));
        return builder.setSignature(ByteString.copyFrom(sig)).build();
    }

    public Message buildMessage(Code code, long ttl, byte[] msg) {
        Message.Builder builder = Message.newBuilder()
                .setCode(code)
                .setCreatedAt(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000))
                .setRemotePeer(self.encodeURI())
                .setTtl(ttl)
                .setNonce(nonce.incrementAndGet())
                .setBody(ByteString.copyFrom(msg));
        byte[] sig = self.getPrivateKey().sign(getRawForSign(builder.build()));
        return builder.setSignature(ByteString.copyFrom(sig)).build();
    }
}

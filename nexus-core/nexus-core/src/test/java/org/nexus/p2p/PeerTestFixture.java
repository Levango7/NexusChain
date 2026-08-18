package org.nexus.p2p;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.apache.commons.codec.binary.Hex;

import java.util.Arrays;

/**
 * Task 234: P2P 测试工具类。
 * <p>
 * 提供构造 {@link Peer}（带固定 peerID，避免每次 Ed25519 keygen 开销）与
 * {@link NexusChainOuterClass.Message}（各 code 分支）的工厂方法。
 */
final class PeerTestFixture {

    private PeerTestFixture() {
    }

    /**
     * 构造一个带指定 peerID（32 字节）的 Peer，host/port 可定制。
     */
    static Peer newPeer(byte[] peerId, String host, int port) {
        Peer p = new Peer();
        p.host = host;
        p.port = port;
        p.peerID = Arrays.copyOf(peerId, 32);
        return p;
    }

    /**
     * 构造一个 peerID 全 0 的 Peer（与 self 相同 subTree 0）。
     */
    static Peer newZeroPeer(String host, int port) {
        return newPeer(new byte[32], host, port);
    }

    /**
     * 构造一个 peerID 第 byteIdx 字节为 val 的 Peer，与 self（全 0）的 subTree = byteIdx*8 + (7 - bitPos)。
     */
    static Peer newPeerWithByte(int byteIdx, byte val, String host, int port) {
        byte[] id = new byte[32];
        id[byteIdx] = val;
        return newPeer(id, host, port);
    }

    /**
     * 构造合法的 nexus:// URL（64 hex 字符 peerID）。
     */
    static String peerUrl(byte[] peerId, String host, int port) {
        return String.format("%s://%s@%s:%d",
                Peer.PROTOCOL_NAME, Hex.encodeHexString(peerId), host, port);
    }

    /**
     * 构造一个 Message，code/body/remotePeer 可定制，createdAt/nonce/ttl 默认。
     */
    static NexusChainOuterClass.Message buildMessage(
            NexusChainOuterClass.Code code,
            ByteString body,
            String remotePeer) {
        NexusChainOuterClass.Message.Builder b = NexusChainOuterClass.Message.newBuilder();
        b.setCode(code);
        b.setCreatedAt(Timestamp.newBuilder().setSeconds(1000L).build());
        b.setRemotePeer(remotePeer);
        b.setTtl(8L);
        b.setNonce(42L);
        b.setSignature(ByteString.copyFromUtf8("sig"));
        if (body != null) {
            b.setBody(body);
        }
        return b.build();
    }

    /**
     * 构造一个 Message，remotePeer 由 Peer.toString() 生成。
     */
    static NexusChainOuterClass.Message buildMessage(
            NexusChainOuterClass.Code code,
            ByteString body,
            Peer remote) {
        return buildMessage(code, body, remote.toString());
    }

    /**
     * 构造一个空 body 的 Message。
     */
    static NexusChainOuterClass.Message buildEmptyBodyMessage(
            NexusChainOuterClass.Code code,
            Peer remote) {
        return buildMessage(code, ByteString.EMPTY, remote.toString());
    }
}
package org.nexus.consortium.net;

import lombok.*;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.nexus.common.HexBytes;
import org.nexus.common.Peer;
import org.nexus.crypto.KeyPair;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Optional;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeerImpl implements org.nexus.common.Peer {
    private static final int PUBLIC_KEY_LENGTH = 32;
    private static final int PRIVATE_KEY_LENGTH = 64;

    private String protocol;
    private String host;
    private int port;
    private HexBytes ID;
    private Ed25519PrivateKey privateKey;
    long score;

    public String toString() {
        return String.format("%s://%s@%s:%d", protocol, ID, host, port);
    }


    @Override
    public String encodeURI() {
        return toString();
    }

    // parse peer from uri like protocol://id@host:port
    // the id may be a 64 byte encoded ed25519 private key (sk[32]+pk[32])
    // or a 32 byte encoded ed25519 public key
    public static Optional<PeerImpl> parse(String url) {
        URI u;
        try {
            u = new URI(url.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        PeerImpl p = new PeerImpl();
        String scheme = u.getScheme();
        p.protocol = (scheme == null || scheme.equals("")) ? PeerServerConfig.DEFAULT_PROTOCOL : scheme;
        p.port = u.getPort();
        if (p.port <= 0) {
            p.port = PeerServerConfig.DEFAULT_PORT;
        }
        p.host = u.getHost();
        if (u.getRawUserInfo() == null || u.getRawUserInfo().equals("")) return Optional.empty();
        try {
            p.ID = new HexBytes(u.getRawUserInfo());
        } catch (DecoderException e) {
            return Optional.empty();
        }
        if (p.ID.size() != PRIVATE_KEY_LENGTH && p.ID.size() != PUBLIC_KEY_LENGTH) {
            return Optional.empty();
        }
        if (p.ID.size() == PRIVATE_KEY_LENGTH) {
            p.privateKey = new Ed25519PrivateKey(p.ID.slice(0, 32).getBytes());
            p.ID = p.ID.slice(32, p.ID.size());
        }
        return Optional.of(p);
    }

    // create self as peer from input
    // if private key is missing, generate key automatically
    public static PeerImpl create(String url) throws Exception {
        URI u = new URI(url);
        String scheme = u.getScheme();
        scheme = (scheme == null || scheme.equals("")) ? PeerServerConfig.DEFAULT_PROTOCOL : scheme;
        int port = u.getPort();
        String host = (u.getHost() == null || u.getHost().trim().equals("")) ? "localhost" : u.getHost();
        port = port <= 0 ? PeerServerConfig.DEFAULT_PORT : port;
        if (u.getRawUserInfo() == null || u.getRawUserInfo().equals("")) {
            KeyPair kp = Ed25519.generateKeyPair();
            url = String.format("%s://%s@%s:%d", scheme,
                    Hex.encodeHexString(kp.getPrivateKey().getEncoded()) + Hex.encodeHexString(kp.getPublicKey().getEncoded()),
                    host, port
            );
        }

        String errorMsg = "failed to parse url " + url;
        return PeerImpl.parse(url).orElseThrow(() -> new Exception(errorMsg));
    }

    public int distance(PeerImpl that) {
        int res = 0;
        byte[] bits = new byte[32];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = (byte) (ID.getBytes()[i] ^ that.ID.getBytes()[i]);
        }
        for (int i = 0; i < bits.length; i++) {
            for (int j = 0; j < 7; j++) {
                res += ((1 << j) & bits[i]) >>> j;
            }
        }
        return res;
    }

    int subTree(byte[] thatID){
        byte[] bits = new byte[32];
        byte mask = (byte) (1 << 7);
        for (int i = 0; i < bits.length; i++) {
            bits[i] = (byte) (ID.getBytes()[i] ^ thatID[i]);
        }
        for (int i = 0; i < 256; i++) {
            if ((bits[i / 8] & (mask >>> (i % 8))) != 0) {
                return i;
            }
        }
        return 0;
    }

    // subtree is less than 256
    int subTree(Peer that) {
        return subTree(that.getID().getBytes());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Peer)) return false;
        Peer peer = (Peer) o;
        return ID.equals(peer.getID());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(ID.getBytes());
    }

    public static void main(String[] args) throws Exception {
        PeerImpl p = PeerImpl.parse("nexus://f43d5ab89d1705cc02131ffe18137e60e0d35e0569cb334f61ca6db7db4c964716d4b57a3de0a6adcf0bc9e3c8da39870bdabc1027fa05b8e25f36484afddfd9@192.168.1.142:9589").get();
        System.out.println(p);
        PeerImpl p2 = create("enode://localhost");
        System.out.println(p2);
    }
}

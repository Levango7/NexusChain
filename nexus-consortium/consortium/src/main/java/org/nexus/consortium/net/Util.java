package org.nexus.consortium.net;

import com.google.common.primitives.Bytes;
import org.nexus.consortium.proto.Message;
import org.nexus.util.BigEndian;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class Util {
    public static byte[] getRawForSign(Message msg) {
        return Bytes.concat(
                BigEndian.encodeInt32(msg.getCode().getNumber()),
                BigEndian.encodeInt64(msg.getCreatedAt().getSeconds()),
                msg.getRemotePeer().getBytes(StandardCharsets.UTF_8),
                BigEndian.encodeInt64(msg.getTtl()),
                BigEndian.encodeInt64(msg.getNonce()),
                msg.getBody().toByteArray()
        );
    }

    public static boolean ping(String host, int port){
        Socket s = new Socket();
        SocketAddress add = new InetSocketAddress(host, port);
        try {
            s.connect(add, 1000);// 超时1秒
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    /**
     * This can be a blocking call with long timeout (thus no ValidateMe)
     */
    public static String bindIp() throws Exception{
        Socket s = new Socket("www.baidu.com", 80);
        return s.getLocalAddress().getHostAddress();
    }

    /**
     * This can be a blocking call with long timeout (thus no ValidateMe)
     */
    static String externalIp() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(
                new URL("http://checkip.amazonaws.com").openStream()));
        String externalIp = in.readLine();
        if (externalIp == null || externalIp.trim().isEmpty()) {
            throw new IOException("Invalid address: '" + externalIp + "'");
        }
        InetAddress.getByName(externalIp);
        return externalIp;
    }

    public static void main(String[] args) throws Exception{
        System.out.println(externalIp());
    }
}

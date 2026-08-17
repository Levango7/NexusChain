package org.nexus.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import jakarta.xml.bind.DatatypeConverter;
import java.util.Date;

/**
 * JWT 工具类。
 * REQ-11/P2: 适配 jjwt 0.12.x API（Jwts.parser().verifyWith().build() / parseSignedClaims().getPayload()）。
 */
public class JWTUtil {
    private final static String APP_ID = "YHWS845682HYESE12yhsd187451289";
    private final static String APP_SECRET = "JHGSYW87453624JHHS";
    private final static String id = "1";
    private final static String issuer = "admin";
    private final static String subject = "JWTToken";

    //Sample method to construct a JWT
    public static String createJWT(long ttlMillis) {

        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);

        //We will sign our JWT with our ApiKey secret
        byte[] apiKeySecretBytes = DatatypeConverter.parseBase64Binary(APP_ID + APP_SECRET);
        // jjwt 0.12.x: 至少 32 字节才能用于 HS256；若 base64 解码后不足则补齐到 32 字节
        if (apiKeySecretBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(apiKeySecretBytes, 0, padded, 0, apiKeySecretBytes.length);
            apiKeySecretBytes = padded;
        }
        SecretKey signingKey = new SecretKeySpec(apiKeySecretBytes, "HmacSHA256");

        //Let's set the JWT Claims
        var builder = Jwts.builder().id(id)
                .issuedAt(now)
                .subject(subject)
                .issuer(issuer)
                .signWith(signingKey, Jwts.SIG.HS256);

        //if it has been specified, let's add the expiration
        if (ttlMillis >= 0) {
            long expMillis = nowMillis + ttlMillis;
            Date exp = new Date(expMillis);
            builder.expiration(exp);
        }

        //Builds the JWT and serializes it to a compact, URL-safe string
        return builder.compact();

    }

    //Sample method to validate and read the JWT
    public static boolean parseJWT(String jwt) {
        try {
            //This line will throw an exception if it is not a signed JWS (as expected)
            byte[] apiKeySecretBytes = DatatypeConverter.parseBase64Binary(APP_ID + APP_SECRET);
            if (apiKeySecretBytes.length < 32) {
                byte[] padded = new byte[32];
                System.arraycopy(apiKeySecretBytes, 0, padded, 0, apiKeySecretBytes.length);
                apiKeySecretBytes = padded;
            }
            SecretKey signingKey = new SecretKeySpec(apiKeySecretBytes, "HmacSHA256");
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
//            System.out.println("ID: " + claims.getId());
//            System.out.println("Subject: " + claims.getSubject());
//            System.out.println("Issuer: " + claims.getIssuer());
//            System.out.println("Expiration: " + claims.getExpiration());
        }catch (Exception e){
            return false;
        }
        return true;
    }

//    public static void main(String[] args) {
//        long exp = 3600000;//过期时间为1h
//        System.out.println("create:"+createJWT(exp));
//
//        boolean claims = JWTUtil.parseJWT("eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiIxIiwiaWF0IjoxNTcyNDE2MTYwLCJzdWIiOiJKV1RUb2tlbiIsImlzcyI6ImFkbWluIiwiZXhwIjoxNTcyNDE5NzYwfQ.oBfuzZVRxiDXiMOGBYdHHKHDJzu9P4Kdb-zdtaD-Jvo");
//        System.out.println(claims);
//    }
}

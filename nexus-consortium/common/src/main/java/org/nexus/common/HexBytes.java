package org.nexus.common;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.primitives.Bytes;
import lombok.NonNull;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.util.Arrays;

/**
 * hex bytes helper for json marshal/unmarshal
 * <p>
 * HexBytes bytes = mapper.readValue("ffff", HexBytes.class);
 * String json = mapper.writeValueAsString(new HexBytes(new byte[32]));
 */
@JsonDeserialize(using = HexBytesUtils.HexBytesDeserializer.class)
@JsonSerialize(using = HexBytesUtils.HexBytesSerializer.class)
public class HexBytes {
    private byte[] bytes;
    private String hexCache;

    public static String encode(byte[] bytes) {
        return Hex.encodeHexString(bytes);
    }

    public static HexBytes parse(String hex) throws DecoderException {
        return new HexBytes(hex);
    }

    public static HexBytes empty() {
        return new HexBytes(new byte[0]);
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int size() {
        return bytes == null ? 0 : bytes.length;
    }

    public String toString() {
        if (hexCache != null) return hexCache;
        hexCache = Hex.encodeHexString(bytes);
        return hexCache;
    }

    public HexBytes() {
        this.bytes = new byte[0];
    }

    public HexBytes(@NonNull byte[]... bytes) {
        this.bytes = Bytes.concat(bytes);
    }

    public HexBytes(String hex) throws DecoderException {
        bytes = Hex.decodeHex(hex.toCharArray());
        hexCache = hex;
    }

    public HexBytes slice(int start, int end) {
        while (start < 0) start += bytes.length;
        while (end < 0) end += bytes.length;
        return new HexBytes(Arrays.copyOfRange(bytes, start, end));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HexBytes hexBytes = (HexBytes) o;
        return Arrays.equals(bytes, hexBytes.bytes);
    }
}

package org.nexus.common;

public class Constants {
    public static final int INTEGER_SIZE = 4;
    public static final int LONG_SIZE = 8;

    public static final int MEGA_BYTES = 1 << 20;

    public static int sizeOf(int any){
        return INTEGER_SIZE;
    }

    public static int sizeOf(long any){
        return LONG_SIZE;
    }

    public static int sizeOf(HexBytes hexBytes){
        return hexBytes == null ? 0 : sizeOf(hexBytes.getBytes());
    }

    public static int sizeOf(byte[] bytes){
        return bytes == null ? 0 : bytes.length;
    }
}

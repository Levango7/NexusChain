package org.nexus.consortium.util;

import java.util.Arrays;

public class BytesReader {
    private byte[] data;
    private int pc;

    public byte[] read(int size){
        if (pc + size > data.length) return new byte[0];
        byte[] res = Arrays.copyOfRange(data, pc, pc + size);
        pc += size;
        return res;
    }

    public byte[] readAll(){
        if (pc >= data.length) return new byte[0];
        byte[] res = Arrays.copyOfRange(data, pc, data.length);
        pc = data.length;
        return res;
    }

    public BytesReader(byte[] data) {
        this.data = data;
    }
}

package org.nexus.gmhelper.cert;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;

public class FileSNAllocator implements CertSNAllocator {
    private static final String SN_FILENAME = "sn.dat";
    private static String snFilePath;

    static {
        ClassLoader loader = FileSNAllocator.class.getClassLoader();
        snFilePath = loader.getResource(SN_FILENAME).getPath();
    }

    public synchronized BigInteger incrementAndGet() throws Exception {
        BigInteger sn = readSN();
        writeSN(sn.add(BigInteger.ONE));
        return sn;
    }

    private BigInteger readSN() throws IOException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(snFilePath, "r");
            byte[] data = new byte[(int) raf.length()];
            int read = raf.read(data);
            if (read != data.length) {
                throw new IOException("SN 文件读取不完整: 期望 " + data.length + " 字节, 实际读到 " + read);
            }
            String snStr = new String(data);
            return new BigInteger(snStr);
        } finally {
            if (raf != null) {
                raf.close();
            }
        }
    }

    private void writeSN(BigInteger sn) throws IOException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(snFilePath, "rw");
            raf.writeBytes(sn.toString(10));
        } finally {
            if (raf != null) {
                raf.close();
            }
        }
    }
}

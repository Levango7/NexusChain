package org.nexus.consortium;

import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.nexus.consortium.util.ByteUtil;
import org.nexus.consortium.util.RLPElement;
import org.nexus.consortium.util.RLPList;
import org.nexus.consortium.util.RLPUtils;

import java.util.ArrayList;
import java.util.List;

public class RLPTest {

    @Test
    public void Test1(){
        List<String> stringList=new ArrayList<String>();
        stringList.add("111");
        stringList.add("222");
        stringList.add("333");
        byte[] eee=new byte[1];
        Test1 test=new Test1(1,1L,"lalala", (byte) 0x01,eee,stringList);

        //encode
        byte[] a1= org.nexus.consortium.util.RLPUtils.encodeInt(test.getA());
        byte[] b1= org.nexus.consortium.util.RLPUtils.encodeElement(ByteUtil.longToBytes(test.getB()));
        byte[] c1= org.nexus.consortium.util.RLPUtils.encodeString(test.getC());
        byte[] d1= org.nexus.consortium.util.RLPUtils.encodeByte(test.getD());
        byte[] e= org.nexus.consortium.util.RLPUtils.encodeElement(test.getE());
        byte[][] f = new byte[test.getF().size()][];
        for(int x=0;x<test.getF().size();x++){
            f[x] = org.nexus.consortium.util.RLPUtils.encodeList(
                    org.nexus.consortium.util.RLPUtils.encodeString(test.getF().get(x)));
        }
        byte[] flist = org.nexus.consortium.util.RLPUtils.encodeList(f);
        byte[] encoded= org.nexus.consortium.util.RLPUtils.encodeList(a1,b1,c1,d1,e,flist);
        System.out.println("RLP:"+encoded.length+"-->"+ Hex.toHexString(encoded));

        //decode
        RLPList paramsList = (RLPList) RLPUtils.decode2(encoded).get(0);
        int a=paramsList.get(0).getRLPInt();
        System.out.println("a:"+a);
        long b=paramsList.get(1).getRLPLong();
        System.out.println("b:"+b);
        String c=paramsList.get(2).getRLPString();
        System.out.println("c:"+c);
        byte d=paramsList.get(3).getRLPByte();
        System.out.println("d:"+d);
        byte[] ee=paramsList.get(4).getRLPBytes();
        System.out.println("e:"+ee.length);
        RLPList caplityList = (RLPList) paramsList.get(5);
        List<String> list=new ArrayList<>();
        for(Object l:caplityList){
            RLPElement capId = ((RLPList) l).get(0);
            list.add(capId.getRLPString());
        }
        System.out.println(list.toString());
    }

    public static  class Test1{
        private int a;
        private long b;
        private String c;
        private byte d;
        private byte[] e;
        private List<String> f;

        public Test1() {
        }

        public Test1(int a, long b, String c, byte d, byte[] e, List<String> f) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.e = e;
            this.f = f;
        }

        public int getA() {
            return a;
        }

        public void setA(int a) {
            this.a = a;
        }

        public long getB() {
            return b;
        }

        public void setB(long b) {
            this.b = b;
        }

        public String getC() {
            return c;
        }

        public void setC(String c) {
            this.c = c;
        }

        public byte getD() {
            return d;
        }

        public void setD(byte d) {
            this.d = d;
        }

        public byte[] getE() {
            return e;
        }

        public void setE(byte[] e) {
            this.e = e;
        }

        public List<String> getF() {
            return f;
        }

        public void setF(List<String> f) {
            this.f = f;
        }
    }
}

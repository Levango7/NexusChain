/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.nexus.consortium.util;

import org.apache.commons.codec.binary.Hex;

import java.util.ArrayList;

/**
 * @author Roman Mandeleil
 * @since 21.04.14
 */
public class RLPList extends ArrayList<RLPElement> implements RLPElement {

    byte[] rlpData;

    public void setRLPData(byte[] rlpData) {
        this.rlpData = rlpData;
    }


    public static void recursivePrint(RLPElement element) {

        if (element == null)
            throw new RuntimeException("RLPElement object can't be null");
        if (element instanceof RLPList) {

            RLPList rlpList = (RLPList) element;
            System.out.print("[");
            for (RLPElement singleElement : rlpList)
                recursivePrint(singleElement);
            System.out.print("]");
        } else {
            String hex = Hex.encodeHexString(element.getRLPBytes());
            System.out.print(hex + ", ");
        }
    }

    @Override
    public byte[] getRLPBytes() {
        return rlpData;
    }

    @Override
    public String getRLPHexString() {
        return Hex.encodeHexString(rlpData);
    }

    @Override
    public String getRLPString() {
        return new String(rlpData);
    }

    @Override
    public int getRLPInt() {
        return ByteUtil.byteArrayToInt(rlpData);
    }

    @Override
    public byte getRLPByte() {
        return rlpData[0];
    }

    @Override
    public long getRLPLong() {
        return ByteUtil.byteArrayToLong(rlpData);
    }
}

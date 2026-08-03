/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.consensus.vrf;

import org.nexus.consensus.Engine;
import org.nexus.crypto.KeyPair;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigInteger;

//@Component
public class VRFDPos implements Engine {
    private NexusChainBlockChain blockChain;
    private KeyPair keyPair;
    private PosTableFactory factory;

    public KeyPair getKeyPair() {
        return keyPair;
    }

    public boolean verifyBlock(Block block) {
        if (block == null) {
            return false;
        }

        if (!verifyHeader(block)) {
            return false;
        }

        return true;
    }

    public boolean verifyHeader(Block header) {
        if (header == null) {
            return false;
        }
        Block currentHeader = blockChain.currentHeader();
        return true;
    }


    public boolean verifyParent(Block parent, Block newHeader) {
        return true;
    }

    public BigInteger BlockWeight(Block block) {
        if (!verifyBlock(block)) {
            return BigInteger.ZERO;
        }
        return BigInteger.ONE;
    }

    @Autowired
    public VRFDPos(NexusChainBlockChain blockChain, KeyPair keyPair, PosTableFactory factory) {
        this.blockChain = blockChain;
        this.keyPair = keyPair;
        this.factory = factory;
    }
}
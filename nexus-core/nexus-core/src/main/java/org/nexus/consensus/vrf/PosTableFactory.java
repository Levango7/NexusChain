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

import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.state.EraLinkedStateFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author sal 1564319846@qq.com
 * pos table factory, lru cached
 */
//@Component
public class PosTableFactory{
    private static final int cacheSize = 20;

    private static final int blocksPerEra = 20;
}
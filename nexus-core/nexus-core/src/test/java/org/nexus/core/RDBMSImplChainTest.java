package org.nexus.core;

public class RDBMSImplChainTest extends NexusChainTest{
    @Override
    public NexusChainBlockChain getChain() {
        return ctx.getBean(RDBMSBlockChainImpl.class);
    }
}

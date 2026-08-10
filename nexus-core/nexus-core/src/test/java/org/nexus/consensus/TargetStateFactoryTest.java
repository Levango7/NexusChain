//package org.nexus.consensus;
//
//import org.nexus.config.TestConfig;
//import org.nexus.consensus.pow.TargetStateFactory;
//import org.nexus.core.Block;
//import org.junit.Test;
//import org.junit.runner.RunWith;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.context.ApplicationContext;
//import org.springframework.test.context.ContextConfiguration;
//import org.springframework.test.context.junit4.SpringRunner;
//
//@RunWith(SpringRunner.class)
//@ContextConfiguration(classes = TestConfig.class)
//public class TargetStateFactoryTest {
//
//    @Autowired
//    private ApplicationContext ctx;
//
//    @Autowired
//    private Block genesis;
//
//    @Test
//    public void test(){
//        TargetStateFactory targetStateFactory = ctx.getBean(TargetStateFactory.class);
//        assert targetStateFactory != null;
//        assert targetStateFactory.getInstance(genesis) != null;
//    }
//}

package org.nexus.consortium;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@SpringBootTest(classes = Start.class)
@RunWith(SpringRunner.class)
public class StartTests {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void test(){
        assert objectMapper != null;
    }
}

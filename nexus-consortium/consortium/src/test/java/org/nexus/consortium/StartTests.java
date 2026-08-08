package org.nexus.consortium;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@SpringBootTest(classes = Start.class)
@ExtendWith(SpringExtension.class)
public class StartTests {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void test(){
        assert objectMapper != null;
    }
}

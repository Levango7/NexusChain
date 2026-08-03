package org.nexus.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CommonTest {
    public static ObjectMapper getObjectMapper(){
        ObjectMapper mapper = new ObjectMapper()
                .enable(JsonParser.Feature.ALLOW_COMMENTS);
        SimpleModule module = new SimpleModule();
        return mapper.registerModule(module);
    }

    @Test
    public void test() throws JsonProcessingException {
        ObjectMapper mapper = getObjectMapper();
        System.out.println(mapper.writeValueAsString(
                Header.builder().build()
        ));
        System.out.println(mapper.writeValueAsString(new Block()));

        Header header = mapper.readValue("{\"createdAt\":\"1572766736\"}", Header.class);
        Block block = mapper.readValue("{\"createdAt\":\"2019-11-03T15:38:56+08:00\", \"body\" : [{\"type\": 100}] }", Block.class);
        assert header.getCreatedAt() == 1572766736L;
        assert block.getCreatedAt() == 1572766736;
        assert block.getBody().get(0).getType() == 100;
        System.out.println(mapper.writeValueAsString(header));
    }
}

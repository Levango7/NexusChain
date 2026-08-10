package org.nexus.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

public class EpochSecondDeserializer extends JsonDeserializer<Long> {
    public static class EpochSecondDeserializeException extends JsonProcessingException{
        public EpochSecondDeserializeException(String msg) {
            super(msg);
        }
    }

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        JsonNode node = p.getCodec().readTree(p);
        String encoded = node.asText();
        if (encoded == null || encoded.equals("")) {
            return 0L;
        }
        try{
            return Long.parseLong(encoded);
        }catch (Exception ignored){}
        try{
            return OffsetDateTime.parse(encoded)
                    .toEpochSecond();
        }catch (Exception ignored){

        }
        throw new EpochSecondDeserializeException("unknown time format "
                + encoded + " expect format " +
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.toString()
        );
    }
}

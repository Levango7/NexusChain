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
        } catch (NumberFormatException notANumber) {
            // P2（2026-09-21）：原为 catch (Exception ignored) {}。
            // 本分支意图是「先按数字解析，失败则回退到日期解析」（见下方 OffsetDateTime），
            // 属合法的多格式尝试。但捕获 Exception 过宽，会连带吞掉真正的意外错误；
            // 收窄为 NumberFormatException，使非预期异常能正常暴露。
        }
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

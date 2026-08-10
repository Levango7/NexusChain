package org.nexus.common;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.apache.commons.codec.binary.Hex;

import java.io.IOException;

class HexBytesUtils {
    static class HexBytesSerializer extends StdSerializer<HexBytes> {
        public HexBytesSerializer() {
            super(HexBytes.class);
        }

        @Override
        public void serialize(HexBytes value, JsonGenerator jgen, SerializerProvider provider) throws IOException, JsonGenerationException {
            jgen.writeString(Hex.encodeHexString(value.getBytes()));
        }
    }

    static class HexBytesDeserializer extends StdDeserializer<HexBytes> {
        private static class HexBytesDeserializeException extends JsonProcessingException {
            protected HexBytesDeserializeException(String msg) {
                super(msg);
            }
        }

        public HexBytesDeserializer() {
            super(HexBytes.class);
        }

        @Override
        public HexBytes deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            JsonNode node = p.getCodec().readTree(p);
            String encoded = node.asText();
            if (encoded == null || encoded.equals("")) {
                return new HexBytes(new byte[]{});
            }
            if (encoded.startsWith("0x")){
                encoded = encoded.substring(2);
            }
            try {
                return new HexBytes(encoded);
            } catch (Exception e) {
                throw new HexBytesDeserializeException(String.format("unable to decode hex string %s", encoded));
            }
        }
    }
}

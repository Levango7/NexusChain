package org.nexus.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

public class EpochSecondsSerializer extends JsonSerializer<Long> {
    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter df = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone);
        gen.writeString(df.format(Instant.ofEpochMilli(
                TimeUnit.MILLISECONDS.convert(value, TimeUnit.SECONDS))
        ));
    }
}

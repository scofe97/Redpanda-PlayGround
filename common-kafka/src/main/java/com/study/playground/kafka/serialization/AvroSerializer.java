package com.study.playground.kafka.serialization;

import org.apache.avro.Schema;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.io.*;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class AvroSerializer {

    private static final SpecificData MODEL;

    static {
        MODEL = new SpecificData();
        MODEL.addLogicalTypeConversion(new TimeConversions.TimestampMillisConversion());
    }

    private AvroSerializer() {}

    public static byte[] serialize(SpecificRecord record) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            new SpecificDatumWriter<>(record.getSchema(), MODEL).write(record, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new AvroSerializationException("Failed to serialize Avro record: " + record.getClass().getSimpleName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends SpecificRecord> T deserialize(byte[] data, Schema schema) {
        try {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(data, null);
            return (T) new SpecificDatumReader<>(schema, schema, MODEL).read(null, decoder);
        } catch (IOException e) {
            throw new AvroSerializationException("Failed to deserialize Avro record: " + schema.getName(), e);
        }
    }

    public static String toJson(SpecificRecord record) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonEncoder encoder = EncoderFactory.get().jsonEncoder(record.getSchema(), out);
            new SpecificDatumWriter<>(record.getSchema(), MODEL).write(record, encoder);
            encoder.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AvroSerializationException("Failed to convert Avro record to JSON: " + record.getClass().getSimpleName(), e);
        }
    }
}

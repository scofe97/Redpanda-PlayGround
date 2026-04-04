package com.study.playground.kafka.serialization;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.Schema;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Confluent Schema Registry 연동 Avro 직렬화 유틸리티.
 * <p>
 * serialize/deserialize: Confluent wire format (magic byte 0x00 + 4-byte schema ID + Avro binary).
 * Schema Registry에 스키마를 자동 등록하며, Redpanda Console에서 메시지를 정상 표시할 수 있다.
 * <p>
 * toJson: Schema Registry 불필요. Avro의 JsonEncoder만 사용하여 JSON 문자열을 반환한다.
 * Redpanda Connect(Bloblang)에서 파싱 가능한 형태.
 */
@Component
public class AvroSerializer {

    /** Avro logical type(Instant 등)을 올바르게 변환하기 위한 커스텀 SpecificData 모델 */
    private static final SpecificData MODEL;

    static {
        MODEL = new SpecificData();
        MODEL.addLogicalTypeConversion(new TimeConversions.TimestampMillisConversion());
    }

    private final KafkaAvroSerializer serializer;
    private final KafkaAvroDeserializer deserializer;

    /**
     * Schema Registry URL을 주입받아 Confluent serializer/deserializer를 초기화한다.
     * RecordNameStrategy를 사용하여 레코드 클래스명으로 subject를 결정한다.
     * Outbox 패턴에서 직렬화 시점에 topic을 알 수 없으므로(serialize(null, record))
     * TopicNameStrategy는 사용할 수 없다. 토픽 분리(1토픽-1스키마)는 코드 레벨에서 보장한다.
     */
    public AvroSerializer(@Value("${spring.kafka.properties.schema.registry.url}") String schemaRegistryUrl) {
        Map<String, Object> config = Map.of(
                "schema.registry.url", schemaRegistryUrl
                , "auto.register.schemas", true
                , "specific.avro.reader", true
                , "value.subject.name.strategy", "io.confluent.kafka.serializers.subject.RecordNameStrategy"
        );
        this.serializer = new KafkaAvroSerializer();
        this.serializer.configure(config, false);
        this.deserializer = new KafkaAvroDeserializer();
        this.deserializer.configure(config, false);
    }

    /** Confluent wire format(0x00 + schema ID + binary)으로 직렬화한다. topic은 null — RecordNameStrategy이므로 불필요. */
    public byte[] serialize(SpecificRecord record) {
        try {
            return serializer.serialize(null, record);
        } catch (Exception e) {
            throw new AvroSerializationException("Failed to serialize Avro record: " + record.getClass().getSimpleName(), e);
        }
    }

    /** Confluent wire format 바이트 배열을 SpecificRecord로 역직렬화한다. schema 파라미터는 타입 힌트용. */
    public <T extends SpecificRecord> T deserialize(byte[] data, Schema schema) {
        try {
            return (T) deserializer.deserialize(null, data);
        } catch (Exception e) {
            throw new AvroSerializationException("Failed to deserialize Avro record: " + schema.getName(), e);
        }
    }

    /**
     * Avro 레코드를 JSON 문자열로 변환한다. Schema Registry를 사용하지 않는다.
     * Redpanda Connect에서 Bloblang으로 파싱할 수 있는 형태.
     */
    public String toJson(SpecificRecord record) {
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

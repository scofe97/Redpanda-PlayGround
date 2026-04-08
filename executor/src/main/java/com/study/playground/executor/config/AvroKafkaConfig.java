package com.study.playground.executor.config;

import com.study.playground.kafka.serialization.AvroSerializer;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Avro 메시지를 @Payload로 바로 받기 위한 Kafka Consumer 설정.
 * common-kafka의 AvroSerializer를 Deserializer로 래핑하여 사용한다.
 */
@Configuration
public class AvroKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> avroListenerFactory(
            AvroSerializer avroSerializer
    ) {
        Deserializer<Object> avroDeserializer = (topic, data) ->
                avroSerializer.deserialize(data, null);

        var consumerFactory = new DefaultKafkaConsumerFactory<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG
                        , bootstrapServers
                        , ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG
                        , StringDeserializer.class
                )
                , new StringDeserializer()
                , avroDeserializer
        );

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    @Bean
    public ProducerFactory<String, Object> avroRetryProducerFactory(
            KafkaProperties kafkaProperties
            , AvroSerializer avroSerializer
    ) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        Serializer<Object> valueSerializer = (topic, data) -> {
            if (data == null) {
                return null;
            }
            if (data instanceof byte[] bytes) {
                return bytes;
            }
            if (data instanceof SpecificRecord record) {
                return avroSerializer.serialize(record);
            }
            throw new SerializationException("Unsupported retry payload type: " + data.getClass().getName());
        };

        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, Object> avroRetryKafkaTemplate(
            ProducerFactory<String, Object> avroRetryProducerFactory
    ) {
        return new KafkaTemplate<>(avroRetryProducerFactory);
    }
}

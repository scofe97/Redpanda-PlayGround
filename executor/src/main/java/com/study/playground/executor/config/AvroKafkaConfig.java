package com.study.playground.executor.config;

import com.study.playground.kafka.serialization.AvroSerializer;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

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
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers
                        , ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
                )
                , new StringDeserializer()
                , avroDeserializer
        );

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
}

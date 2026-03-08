package com.study.playground.kafka.config;

import com.study.playground.kafka.interceptor.CloudEventsHeaderInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableKafka
@EnableScheduling
@RequiredArgsConstructor
public class KafkaProducerConfig {

    private final CloudEventsHeaderInterceptor cloudEventsHeaderInterceptor;

    @Bean
    public ProducerFactory<String, byte[]> producerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(null));
    }

    @Bean
    public KafkaTemplate<String, byte[]> kafkaTemplate(ProducerFactory<String, byte[]> producerFactory) {
        KafkaTemplate<String, byte[]> template = new KafkaTemplate<>(producerFactory);
        template.setProducerInterceptor(cloudEventsHeaderInterceptor);
        return template;
    }
}

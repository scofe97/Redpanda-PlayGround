package com.study.playground.kafka.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Outbox 자동 설정.
 *
 * {@code @MapperScan}을 사용하지 않는 이유: {@code @MapperScan}은 MyBatis의
 * {@code @Mapper} 어노테이션 자동 감지를 비활성화하여 app 모듈의 다른 Mapper들이
 * 스캔되지 않는다. OutboxMapper에 {@code @Mapper}가 있고 base package
 * ({@code com.study.playground}) 하위이므로 자동 감지로 충분하다.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    public OutboxMetrics outboxMetrics(MeterRegistry registry, OutboxMapper outboxMapper) {
        return new OutboxMetrics(registry, outboxMapper);
    }
}

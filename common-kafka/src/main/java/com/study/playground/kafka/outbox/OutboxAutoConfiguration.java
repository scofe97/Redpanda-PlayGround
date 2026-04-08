package com.study.playground.kafka.outbox;

import com.study.playground.kafka.outbox.domain.OutboxRetryPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Outbox 자동 설정.
 *
 * <p>JPA Repository 기반으로 전환되어 {@code @EntityScan}은 Spring Boot
 * 자동 감지에 의해 처리된다. base package({@code com.study.playground}) 하위이므로
 * 별도 설정 없이 엔티티와 Repository가 자동 등록된다.
 *
 * <p>{@code OutboxRetryPolicy}는 순수 도메인 서비스(Spring 어노테이션 없음)이므로
 * executor 모듈의 {@code DomainServiceConfig}와 동일하게 여기서 Bean으로 등록한다.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    public OutboxMetrics outboxMetrics(MeterRegistry registry, OutboxEventRepository outboxEventRepository) {
        return new OutboxMetrics(registry, outboxEventRepository);
    }

    @Bean
    public OutboxRetryPolicy outboxRetryPolicy() {
        return new OutboxRetryPolicy();
    }
}

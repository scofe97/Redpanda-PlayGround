package com.study.playground.operator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.study.playground.operator"
        , "com.study.playground.kafka"
        , "com.study.playground.common"
})
@EntityScan(basePackages = {
        "com.study.playground.operator"
        , "com.study.playground.kafka.outbox"
        , "com.study.playground.common.idempotency"
})
@EnableJpaRepositories(basePackages = {
        "com.study.playground.operator"
        , "com.study.playground.kafka.outbox"
        , "com.study.playground.common.idempotency"
})
@EnableScheduling
public class OperatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperatorApplication.class, args);
    }
}

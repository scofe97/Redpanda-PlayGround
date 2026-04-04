package com.study.playground.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.study.playground.executor"
        , "com.study.playground.kafka"
})
@EntityScan(basePackages = {
        "com.study.playground.executor.dispatch.infrastructure.persistence"
        , "com.study.playground.kafka.outbox"
})
@EnableJpaRepositories(basePackages = {
        "com.study.playground.executor.dispatch.infrastructure.persistence"
        , "com.study.playground.executor.runner.infrastructure.persistence"
        , "com.study.playground.kafka.outbox"
})
@EnableScheduling
public class ExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutorApplication.class, args);
    }
}

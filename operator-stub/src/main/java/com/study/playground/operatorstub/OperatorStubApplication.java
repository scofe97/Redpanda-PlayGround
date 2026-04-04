package com.study.playground.operatorstub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
        "com.study.playground.operatorstub"
        , "com.study.playground.kafka"
})
@EntityScan(basePackages = {
        "com.study.playground.operatorstub.domain"
        , "com.study.playground.kafka.outbox"
})
@EnableJpaRepositories(basePackages = {
        "com.study.playground.operatorstub.domain"
        , "com.study.playground.kafka.outbox"
})
public class OperatorStubApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperatorStubApplication.class, args);
    }
}

package com.study.playground.pipeline.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 파이프라인 모듈의 인프라 빈 설정.
 */
@Configuration
public class PipelineConfig {

    /**
     * DAG Job 병렬 실행용 스레드 풀.
     * Jenkins containerCap(3)에 맞춰 최대 3개 스레드를 사용한다.
     * 동시에 3개 이상의 Jenkins 빌드를 트리거해도 Agent 리소스가 부족해지므로
     * 스레드 풀 크기를 동일하게 제한한다.
     */
    @Bean(value = "jobExecutorPool", destroyMethod = "shutdown")
    public ExecutorService jobExecutorPool() {
        return Executors.newFixedThreadPool(3);
    }
}

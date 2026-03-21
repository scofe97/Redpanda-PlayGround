package com.study.playground.pipeline.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 파이프라인 모듈의 인프라 빈 설정.
 */
@Configuration
@EnableConfigurationProperties(PipelineProperties.class)
public class PipelineConfig {

    /**
     * DAG Job 병렬 실행용 스레드 풀.
     * Jenkins containerCap에 맞춰 동시 실행 수를 제한한다.
     */
    @Bean(value = "jobExecutorPool", destroyMethod = "shutdown")
    public ExecutorService jobExecutorPool(PipelineProperties props) {
        return Executors.newFixedThreadPool(props.maxConcurrentJobs());
    }

    /**
     * Job 재시도용 스케줄러. exponential backoff delay를 위해 ScheduledExecutorService를 사용한다.
     */
    @Bean(value = "retryScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService retryScheduler() {
        return Executors.newScheduledThreadPool(2);
    }
}

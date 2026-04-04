package com.study.playground.executor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "executor")
@Getter
@Setter
public class ExecutorProperties {

    /** 디스패치 배치 크기 상한 */
    private int maxBatchSize = 5;

    /** Job 최대 재시도 횟수 */
    private int jobMaxRetries = 2;

    /** Job 타임아웃 (분) — 타임아웃 스케줄러가 RUNNING 판단에 사용 */
    private int jobTimeoutMinutes = 30;

    /** 타임아웃 체크 주기 (ms) */
    private long timeoutCheckIntervalMs = 60000;
}

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

    /** 디스패치 스케줄러 주기 (ms) */
    private long dispatchIntervalMs = 3000;

    /** 디스패치 스케줄러 초기 지연 (ms) */
    private long dispatchInitialDelayMs = 0;

    /** SUBMITTED 상태 체류 허용 시간 (초) — 이 시간 초과 시 스케줄러가 방어 */
    private int submittedStaleSeconds = 30;

    /** SUBMITTED 방어 스케줄러 주기 (ms) */
    private long submittedCheckIntervalMs = 10000;

    /** SUBMITTED 방어 스케줄러 초기 지연 (ms) */
    private long submittedCheckInitialDelayMs = 0;

    /** RUNNING 상태 체류 허용 시간 (분) — 이 시간 초과 시 빌드 상태 확인 */
    private int runningStaleMinutes = 10;

    /** RUNNING 방어 스케줄러 초기 지연 (ms) */
    private long timeoutCheckInitialDelayMs = 0;

    /** QUEUED 상태 체류 허용 시간 (초) — 이 시간 초과 시 스케줄러가 방어 */
    private int queuedStaleSeconds = 60;

    /** QUEUED 방어 스케줄러 주기 (ms) */
    private long queuedCheckIntervalMs = 15000;

    /** QUEUED 방어 스케줄러 초기 지연 (ms) */
    private long queuedCheckInitialDelayMs = 0;

    /** 공용 scheduler thread pool 크기 */
    private int schedulerPoolSize = 4;
}

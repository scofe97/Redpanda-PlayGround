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

    /** 디스패치 1회에서 평가할 최대 PENDING 건수. */
    private int maxBatchSize = 5;
    /** Jenkins 호출 실패나 stale recovery 시 허용할 최대 재시도 횟수. */
    private int jobMaxRetries = 2;
    /** RUNNING 상태가 이 시간 이상 지속되면 timeout 방어 대상으로 본다. */
    private int jobTimeoutMinutes = 30;
    /** RUNNING timeout 스케줄러의 실행 주기. */
    private long timeoutCheckIntervalMs = 60000;
    /** PENDING -> QUEUED 평가 스케줄러의 실행 주기. */
    private long dispatchIntervalMs = 3000;
    /** 디스패치 스케줄러의 초기 지연 시간. */
    private long dispatchInitialDelayMs = 0;
    /** SUBMITTED 상태를 정상으로 볼 최대 체류 시간. */
    private int submittedStaleSeconds = 30;
    /** SUBMITTED stale recovery 스케줄러의 실행 주기. */
    private long submittedCheckIntervalMs = 10000;
    /** SUBMITTED stale recovery 스케줄러의 초기 지연 시간. */
    private long submittedCheckInitialDelayMs = 0;
    /** RUNNING 상태를 Jenkins 재조회 대상으로 판단할 기준 시간. */
    private int runningStaleMinutes = 10;
    /** RUNNING timeout 스케줄러의 초기 지연 시간. */
    private long timeoutCheckInitialDelayMs = 0;
    /** QUEUED 상태를 execute 유실 후보로 판단할 기준 시간. */
    private int queuedStaleSeconds = 60;
    /** QUEUED stale recovery 스케줄러의 실행 주기. */
    private long queuedCheckIntervalMs = 15000;
    /** QUEUED stale recovery 스케줄러의 초기 지연 시간. */
    private long queuedCheckInitialDelayMs = 0;
    /** executor 내부 스케줄러들이 공유하는 thread pool 크기. */
    private int schedulerPoolSize = 4;
    /** operator가 기록한 Jenkins health 정보를 신뢰할 최대 시간. */
    private int jenkinsHealthStalenessMinutes = 3;
    /** 동적 Pod Jenkins(K8S)로 판단될 때 애플리케이션이 적용할 디스패치 슬롯 수. */
    private int dynamicK8sDispatchCapacity = 5;
}

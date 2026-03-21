package com.study.playground.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 파이프라인 엔진의 외부화된 설정값.
 *
 * <p>{@code application.yml}의 {@code pipeline.*} 프로퍼티에 바인딩된다.
 * 기본값은 개발 환경 기준이며, 프로덕션에서는 환경 변수나 프로필 설정으로 조정한다.</p>
 *
 * @param maxConcurrentJobs           동시 실행 가능한 최대 Job 수. Jenkins containerCap에 맞춘다.
 * @param webhookTimeoutMinutes       webhook 응답 대기 허용 시간(분). 초과 시 타임아웃 처리.
 * @param jobMaxRetries               동기 실행 실패 시 최대 재시도 횟수.
 * @param staleExecutionTimeoutMinutes RUNNING 상태에서 진전 없는 실행을 FAILED로 전환하는 시간(분).
 */
@ConfigurationProperties(prefix = "pipeline")
public record PipelineProperties(
        int maxConcurrentJobs,
        int webhookTimeoutMinutes,
        int jobMaxRetries,
        int staleExecutionTimeoutMinutes
) {
    public PipelineProperties {
        if (maxConcurrentJobs <= 0) maxConcurrentJobs = 3;
        if (webhookTimeoutMinutes <= 0) webhookTimeoutMinutes = 5;
        if (jobMaxRetries < 0) jobMaxRetries = 2;
        if (staleExecutionTimeoutMinutes <= 0) staleExecutionTimeoutMinutes = 30;
    }

    /**
     * 기본값으로 생성한다. Spring 바인딩 실패 시 폴백용.
     */
    public PipelineProperties() {
        this(3, 5, 2, 30);
    }
}

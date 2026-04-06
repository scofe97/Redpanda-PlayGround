package com.study.playground.executor.dispatch.domain.port.out;

import com.study.playground.executor.dispatch.domain.model.JobDefinitionInfo;

/**
 * Job 정의 조회 out-port.
 * pipeline_job → purpose → purpose_entry → support_tool 조인으로
 * Jenkins 인스턴스 및 경로 정보를 조회한다.
 */
public interface JobDefinitionQueryPort {
    JobDefinitionInfo load(String jobId);
}

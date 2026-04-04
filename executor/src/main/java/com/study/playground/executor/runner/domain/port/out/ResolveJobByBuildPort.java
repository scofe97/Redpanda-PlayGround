package com.study.playground.executor.runner.domain.port.out;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;

import java.util.Optional;

/**
 * Jenkins 빌드 정보로 ExecutionJob을 매칭하는 out-port.
 *
 * Jenkins는 jobExcnId를 모르므로, (jobId + buildNo)로 executor DB에서 매칭한다.
 * jobId는 여러 실행 건이 있을 수 있으므로 buildNo로 특정한다.
 *
 * @see com.study.playground.executor.runner.infrastructure.persistence.BuildJobMatchAdapter
 */
public interface ResolveJobByBuildPort {

    /**
     * jobId + buildNo로 ExecutionJob을 찾는다.
     *
     * @param jobId   Jenkins 경로에서 추출한 Job 정의 식별자
     * @param buildNo Jenkins 빌드 번호
     * @return 매칭된 ExecutionJob, 없으면 empty
     */
    Optional<ExecutionJob> findByJobIdAndBuildNo(String jobId, int buildNo);
}
